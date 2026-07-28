package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

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
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * The attested dispatch path, after the DOCUMENT attestation work moved into document_vault.
 *
 * <h2>Why these expectations inverted</h2>
 *
 * This class previously asserted that an attested publish REPLAYED a 1447 map frozen during the
 * ceremony — reusing its {@code ipfs_cid} verbatim, never re-pinning, and never calling
 * {@code DocumentMetadataSerialiser}. That could not survive the split deployment: the tier that runs
 * ceremonies ({@code api}) has neither IPFS credentials nor chain access, so it cannot build a
 * manifest to freeze in the first place.
 *
 * <p>The wallet now attests a content commitment, and THIS tier assembles the manifest for attested
 * and plain publishes alike. The attestation arrives on the dispatch record rather than being looked
 * up, which is what lets this module drop its dependency on document_vault entirely.
 */
class DocumentL1TransactionCreatorAttestationTest {

    private static final ChainTip CHAIN_TIP = ChainTip.builder()
            .absoluteSlot(99999L)
            .blockHash("fresh-tip")
            .epochNo(Optional.of(500))
            .network(CardanoNetwork.MAIN)
            .isSynced(true)
            .build();

    private static final String HASH_A = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";

    private BackendService backendService;
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private MetadataChecker jsonSchemaMetadataChecker;
    private OrganisationPublicApi organisationPublicApi;
    private Account organiserWallet;
    private DocumentConverter documentConverter;
    private DocumentIpfsSerialiser documentIpfsSerialiser;
    private DocumentMetadataSerialiser documentMetadataSerialiser;
    private IpfsPublisher ipfsPublisher;
    private Cip170MetadataFactory cip170MetadataFactory;

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
        cip170MetadataFactory = mock(Cip170MetadataFactory.class);

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
        entity.setSlots(List.of(new DocumentEntity.Slot("d".repeat(64), "e".repeat(96), HASH_A)));
        entity.setAttestationCeremonyId(ceremonyId);
        if (ceremonyId != null) {
            entity.setAttestationAid("Eaid-user-1");
            // payloadSaid, NOT the commitment digest, is what becomes the on-chain 170.d.
            entity.setAttestationPayloadSaid("Epayloadsaid-1");
            entity.setAttestationKelSequence("5");
        }

        return entity;
    }

    /**
     * A spy with {@code serialiseTransaction} stubbed: actually building the Cardano transaction needs a
     * funded wallet and a live backend, neither of which this test is about. Same technique as
     * {@code DocumentL1TransactionCreatorTest}'s happy-path test.
     */
    private DocumentL1TransactionCreator creator() throws Exception {
        DocumentL1TransactionCreator real = new DocumentL1TransactionCreator(
                backendService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                jsonSchemaMetadataChecker,
                organisationPublicApi,
                organiserWallet,
                Optional.of(ipfsPublisher),
                cip170MetadataFactory,
                1447,
                false);
        DocumentL1TransactionCreator spied = spy(real);
        doReturn(new byte[]{1, 2, 3}).when(spied).serialiseTransaction(any(Metadata.class));

        return spied;
    }

    private void stubHappyPath() {
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("bafy-fresh-cid"));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(documentMetadataSerialiser.serialiseToMetadataMap(any(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(manifestFixture());
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);
    }

    private static MetadataMap manifestFixture() {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("type", "DOCUMENT");

        return map;
    }

    /**
     * The core inversion: an attested publish now pins the envelope and builds the manifest here, in
     * the tier that actually holds IPFS credentials and chain access.
     */
    @Test
    void anAttestedPublishPinsIpfsAndBuildsTheManifestItself() throws Exception {
        stubHappyPath();
        when(cip170MetadataFactory.attestMap(anyString(), anyString(), anyString()))
                .thenReturn(MetadataBuilder.createMap());

        DocumentEntity document = fixture("cer-1");
        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator().pullBlockchainTransaction("org-1", document);

        assertThat(result.isRight()).isTrue();
        verify(ipfsPublisher).publish(anyString());
        verify(documentMetadataSerialiser).serialiseToMetadataMap(any(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString());
        // The freshly pinned CID is recorded on the dispatch record.
        assertThat(document.getIpfsCid()).isEqualTo("bafy-fresh-cid");
    }

    /**
     * {@code 170.d} must carry the payload SAID the wallet's KEL anchored — not the commitment digest.
     * Conflating the two produced a design a real Veridian wallet never accepted.
     */
    @Test
    void theAttestMapIsBuiltFromTheAttestationCarriedOnTheRecord() throws Exception {
        stubHappyPath();
        when(cip170MetadataFactory.attestMap(anyString(), anyString(), anyString()))
                .thenReturn(MetadataBuilder.createMap());

        assertThat(creator().pullBlockchainTransaction("org-1", fixture("cer-1")).isRight()).isTrue();

        verify(cip170MetadataFactory).attestMap("Eaid-user-1", "Epayloadsaid-1", "5");
    }

    /** A plain publish must never build an ATTEST map. */
    @Test
    void aPlainPublishAttachesNoAttestMap() throws Exception {
        stubHappyPath();

        assertThat(creator().pullBlockchainTransaction("org-1", fixture(null)).isRight()).isTrue();

        verify(cip170MetadataFactory, never()).attestMap(anyString(), anyString(), anyString());
    }

    /**
     * Fails closed rather than silently publishing unattested. A record carrying a ceremony id but no
     * consumed attestation could only come from a vault that skipped consumption, so it is a defect,
     * not a case to paper over — publishing it as plain would drop the holder's attestation silently.
     */
    @Test
    void aCeremonyIdWithoutAConsumedAttestationFailsClosed() throws Exception {
        stubHappyPath();

        DocumentEntity broken = fixture("cer-1");
        broken.setAttestationAid(null);
        broken.setAttestationPayloadSaid(null);

        assertThatThrownBy(() -> creator().pullBlockchainTransaction("org-1", broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to publish it as unattested");
    }

    /** IPFS is mandatory for documents on both paths — never inlined, never skipped. */
    @Test
    void noIpfsPublisherFailsBeforeTouchingTheChain() {
        DocumentL1TransactionCreator noIpfs = new DocumentL1TransactionCreator(
                backendService, documentConverter, documentIpfsSerialiser, documentMetadataSerialiser,
                blockchainReaderPublicApi, jsonSchemaMetadataChecker, organisationPublicApi, organiserWallet,
                Optional.empty(), cip170MetadataFactory, 1447, false);

        Either<ProblemDetail, API3BlockchainTransaction> result =
                noIpfs.pullBlockchainTransaction("org-1", fixture("cer-1"));

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("DOCUMENT_PUBLISHING_UNAVAILABLE");
        verify(blockchainReaderPublicApi, never()).getChainTip();
    }
}
