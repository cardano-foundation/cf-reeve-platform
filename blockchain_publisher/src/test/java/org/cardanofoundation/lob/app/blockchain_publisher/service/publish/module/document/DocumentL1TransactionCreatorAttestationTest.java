package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.CardanoNetwork;
import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationLookup;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationLookup.AttestedDispatchData;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * The fail-closed attested-dispatch hook (design §5.3, Task 15): a {@link DocumentEntity} carrying a
 * non-null {@code attestationCeremonyId} must route through {@link DocumentAttestationLookup} and
 * MUST NEVER fall back to the plain-publish path {@link DocumentL1TransactionCreatorTest} covers -
 * every failure mode here is a {@code Left}, never a silently-degraded transaction. Regression: a
 * {@code null} ceremony id is covered by {@link DocumentL1TransactionCreatorTest} and is asserted here
 * (once) to never touch the {@link DocumentAttestationLookup} collaborator at all.
 */
class DocumentL1TransactionCreatorAttestationTest {

    private static final ChainTip CHAIN_TIP = ChainTip.builder()
            .absoluteSlot(99999L)
            .blockHash("fresh-tip")
            .epochNo(Optional.of(500))
            .network(CardanoNetwork.MAIN)
            .isSynced(true)
            .build();

    private BackendService backendService;
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private MetadataChecker jsonSchemaMetadataChecker;
    private OrganisationPublicApi organisationPublicApi;
    private Account organiserWallet;
    private DocumentConverter documentConverter;
    private DocumentIpfsSerialiser documentIpfsSerialiser;
    private DocumentMetadataSerialiser documentMetadataSerialiser;
    private IpfsPublisher ipfsPublisher;
    private DocumentAttestationLookup attestationLookup;

    @BeforeEach
    void setUp() {
        backendService = mock(BackendService.class);
        blockchainReaderPublicApi = mock(BlockchainReaderPublicApiIF.class);
        jsonSchemaMetadataChecker = mock(MetadataChecker.class);
        organisationPublicApi = mock(OrganisationPublicApi.class);
        organiserWallet = new Account();
        documentConverter = new DocumentConverter();
        documentIpfsSerialiser = new DocumentIpfsSerialiser(new ObjectMapper());
        documentMetadataSerialiser = mock(DocumentMetadataSerialiser.class);
        ipfsPublisher = mock(IpfsPublisher.class);
        attestationLookup = mock(DocumentAttestationLookup.class);

        // Only the plain-publish regression test below (nullCeremonyIdNeverTouchesTheAttestationLookup)
        // reaches organisation resolution - every other test in this class exercises the attested path,
        // which never calls organisationPublicApi at all.
        when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.of(Organisation.builder()
                .id("org-1")
                .name("Acme")
                .taxIdNumber("TAX-1")
                .countryCode("CH")
                .accountPeriodDays(365)
                .currencyId("ISO_4217:CHF")
                .reportCurrencyId("ISO_4217:CHF")
                .build()));
    }

    private static DocumentEntity fixture(String ceremonyId) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(new DocumentEntity.Slot("d".repeat(64), "e".repeat(96), "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae")));
        entity.setAttestationCeremonyId(ceremonyId);
        return entity;
    }

    private DocumentL1TransactionCreator creator(Optional<DocumentAttestationLookup> lookup) {
        return new DocumentL1TransactionCreator(
                backendService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                jsonSchemaMetadataChecker,
                organisationPublicApi,
                organiserWallet,
                Optional.of(ipfsPublisher),
                lookup,
                1447,
                false);
    }

    private static MetadataMap frozenMap() {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("ipfs_cid", "bafy-frozen-cid");
        return map;
    }

    private static ConsumedAttestation consumedFixture() {
        // digestQb64 (1447/freeze digest) and payloadSaid (the value attestMap actually publishes as
        // the on-chain 170.d, design §4.4 rev 3) are deliberately distinct here.
        return new ConsumedAttestation("cer-1", "Eaid-user-1", "Edigest-1", "Epayloadsaid-1", "1447", "5");
    }

    // --- lookup absent (module disabled) - fail closed, never plain-publish ---

    @Test
    void ceremonyIdWithNoLookupConfiguredFailsClosedAndNeverTouchesIpfsOrTheChain() {
        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator(Optional.empty()).pullBlockchainTransaction("org-1", fixture("cer-1"));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTATION_UNAVAILABLE);

        verifyNoInteractions(ipfsPublisher, blockchainReaderPublicApi, documentMetadataSerialiser);
    }

    // --- lookup present but loadForDispatch rejects (freeze missing / not consumed / mismatch) ---

    @Test
    void loadForDispatchLeftPropagatesWithoutBuildingATransaction() {
        ProblemDetail freezeMissing = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "no freeze");
        freezeMissing.setTitle(VaultProblems.ATTESTATION_FREEZE_MISSING);
        when(attestationLookup.loadForDispatch("doc-1", "cer-1")).thenReturn(Either.left(freezeMissing));

        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator(Optional.of(attestationLookup)).pullBlockchainTransaction("org-1", fixture("cer-1"));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ATTESTATION_FREEZE_MISSING);
        verifyNoInteractions(ipfsPublisher, blockchainReaderPublicApi);
    }

    // --- happy path: both labels present, frozen cid reused, fresh tip used for creationSlot ---

    @Test
    void attestedHappyPathCarriesBothLabelsReusesFrozenCidAndUsesFreshChainTip() throws Exception {
        MetadataMap frozen = frozenMap();
        ConsumedAttestation consumed = consumedFixture();
        AttestedDispatchData data = new AttestedDispatchData(frozen, "bafy-frozen-cid", consumed);
        when(attestationLookup.loadForDispatch("doc-1", "cer-1")).thenReturn(Either.right(data));
        when(attestationLookup.attestMap(consumed)).thenAnswer(inv -> {
            MetadataMap attestMap = MetadataBuilder.createMap();
            attestMap.put("t", "ATTEST");
            attestMap.put("s", consumed.kelSequence());
            attestMap.put("i", consumed.aid());
            attestMap.put("d", consumed.payloadSaid());
            return attestMap;
        });
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);

        AtomicReference<Metadata> capturedMetadata = new AtomicReference<>();
        DocumentL1TransactionCreator creator = spy(creator(Optional.of(attestationLookup)));
        doAnswer(invocation -> {
            Metadata metadata = invocation.getArgument(0);
            capturedMetadata.set(metadata);
            return new byte[]{1, 2, 3};
        }).when(creator).serialiseTransaction(any(Metadata.class));

        DocumentEntity document = fixture("cer-1");
        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator.pullBlockchainTransaction("org-1", document);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().creationSlot()).isEqualTo(99999L); // fresh tip, not any frozen slot
        assertThat(document.getIpfsCid()).isEqualTo("bafy-frozen-cid");

        Metadata metadata = capturedMetadata.get();
        MetadataMap label1447 = (MetadataMap) metadata.get(java.math.BigInteger.valueOf(1447));
        assertThat(label1447.get("ipfs_cid")).isEqualTo("bafy-frozen-cid");

        MetadataMap label170 = (MetadataMap) metadata.get(java.math.BigInteger.valueOf(170));
        assertThat(label170.get("t")).isEqualTo("ATTEST");
        assertThat(label170.get("i")).isEqualTo("Eaid-user-1");
        assertThat(label170.get("d")).isEqualTo("Epayloadsaid-1");
        assertThat(label170.get("s")).isEqualTo("5");

        // Frozen cid reused verbatim - IPFS is NEVER touched on the attested path.
        verifyNoInteractions(ipfsPublisher);
    }

    @Test
    void attestedPathNeverCallsDocumentMetadataSerialiser() throws Exception {
        MetadataMap frozen = frozenMap();
        ConsumedAttestation consumed = consumedFixture();
        AttestedDispatchData data = new AttestedDispatchData(frozen, "bafy-frozen-cid", consumed);
        when(attestationLookup.loadForDispatch("doc-1", "cer-1")).thenReturn(Either.right(data));
        when(attestationLookup.attestMap(consumed)).thenReturn(MetadataBuilder.createMap());
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);

        DocumentL1TransactionCreator creator = spy(creator(Optional.of(attestationLookup)));
        doReturn(new byte[]{1, 2, 3}).when(creator).serialiseTransaction(any(Metadata.class));

        creator.pullBlockchainTransaction("org-1", fixture("cer-1"));

        verifyNoInteractions(documentMetadataSerialiser);
    }

    // --- regression: null ceremony id never touches the attestation lookup ---

    @Test
    void nullCeremonyIdNeverTouchesTheAttestationLookup() throws Exception {
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("bafy-plain-cid"));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);
        when(documentMetadataSerialiser.serialiseToMetadataMap(
                any(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MetadataBuilder.createMap());

        DocumentL1TransactionCreator creator = spy(creator(Optional.of(attestationLookup)));
        doReturn(new byte[]{1, 2, 3}).when(creator).serialiseTransaction(any(Metadata.class));

        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator.pullBlockchainTransaction("org-1", fixture(null));

        assertThat(result.isRight()).isTrue();
        verifyNoInteractions(attestationLookup);
    }

}
