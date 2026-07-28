package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.metadata.Metadata;
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
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * Documents require IPFS unconditionally (spec: mandatory IPFS — never inline the envelope into L1 metadata,
 * never silently skip). This is the one genuinely new behaviour vs. the other (optional-IPFS) L1 transaction
 * creators, so it is the thing under test here — the tx assembly/signing tail is copied from
 * {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3L1TransactionCreator}
 * unchanged and is exercised only incidentally.
 */
class DocumentL1TransactionCreatorTest {

    private static final ChainTip CHAIN_TIP = ChainTip.builder()
            .absoluteSlot(12345L)
            .blockHash("abc123")
            .epochNo(Optional.of(500))
            .network(CardanoNetwork.MAIN)
            .isSynced(true)
            .build();

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:15:30Z"), ZoneId.of("UTC"));

    private BackendService backendService;
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private MetadataChecker jsonSchemaMetadataChecker;
    private OrganisationPublicApi organisationPublicApi;
    private Account organiserWallet;
    private DocumentConverter documentConverter;
    private DocumentIpfsSerialiser documentIpfsSerialiser;
    private DocumentMetadataSerialiser documentMetadataSerialiser;

    @BeforeEach
    void setUp() {
        backendService = mock(BackendService.class);
        blockchainReaderPublicApi = mock(BlockchainReaderPublicApiIF.class);
        jsonSchemaMetadataChecker = mock(MetadataChecker.class);
        organisationPublicApi = mock(OrganisationPublicApi.class);
        organiserWallet = new Account();
        documentConverter = new DocumentConverter();
        documentIpfsSerialiser = new DocumentIpfsSerialiser(new ObjectMapper());
        documentMetadataSerialiser = spy(new DocumentMetadataSerialiser(FIXED_CLOCK));

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

    private static DocumentEntity fixture() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(new DocumentEntity.Slot("d".repeat(64), "e".repeat(96), "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae")));
        return entity;
    }

    private DocumentL1TransactionCreator creator(Optional<IpfsPublisher> ipfsPublisher) {
        return new DocumentL1TransactionCreator(
                backendService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                jsonSchemaMetadataChecker,
                organisationPublicApi,
                organiserWallet,
                ipfsPublisher,
                Optional.empty(),
                1447,
                false);
    }

    @Test
    void emptyIpfsPublisher_returnsServiceUnavailableAndNeverTouchesTheChain() {
        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator(Optional.empty()).pullBlockchainTransaction("org-1", fixture());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(result.getLeft().getTitle()).isEqualTo("DOCUMENT_PUBLISHING_UNAVAILABLE");

        verifyNoInteractions(blockchainReaderPublicApi);
    }

    @Test
    void ipfsPublishSucceeds_cidLandsOnTheEntityAndInTheMetadataMap() throws Exception {
        IpfsPublisher ipfsPublisher = mock(IpfsPublisher.class);
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("bafy-cid-1"));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(jsonSchemaMetadataChecker.checkTransactionMetadata(anyString())).thenReturn(true);

        AtomicReference<MetadataMap> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            MetadataMap result = (MetadataMap) invocation.callRealMethod();
            captured.set(result);
            return result;
        }).when(documentMetadataSerialiser).serialiseToMetadataMap(
                any(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());

        DocumentL1TransactionCreator creator = spy(creator(Optional.of(ipfsPublisher)));
        doReturn(new byte[]{1, 2, 3}).when(creator).serialiseTransaction(any(Metadata.class));

        DocumentEntity document = fixture();
        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator.pullBlockchainTransaction("org-1", document);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().creationSlot()).isEqualTo(12345L);
        assertThat(document.getIpfsCid()).isEqualTo("bafy-cid-1");

        MetadataMap data = (MetadataMap) captured.get().get("data");
        assertThat(data.get("ipfs_cid")).isEqualTo("bafy-cid-1");
    }

    @Test
    void ipfsPublishFails_propagatesTheLeftWithoutBuildingATransaction() {
        IpfsPublisher ipfsPublisher = mock(IpfsPublisher.class);
        ProblemDetail ipfsError = ProblemDetail.forStatus(500);
        ipfsError.setTitle("IPFS_UPLOAD_ERROR");
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.left(ipfsError));

        Either<ProblemDetail, API3BlockchainTransaction> result =
                creator(Optional.of(ipfsPublisher)).pullBlockchainTransaction("org-1", fixture());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("IPFS_UPLOAD_ERROR");
        verifyNoInteractions(blockchainReaderPublicApi, jsonSchemaMetadataChecker);
    }

    /**
     * Organisation resolution moved into this class with WS3 step 1 ({@code DocumentMetadataSerialiser}
     * no longer looks it up itself) - this pins the exact same fail-fast behaviour
     * {@code DocumentMetadataSerialiserTest#unknownOrganisation_throwsInsteadOfPublishingWithoutOrgSection}
     * used to cover before the move.
     */
    @Test
    void unknownOrganisation_throwsInsteadOfPublishingWithoutOrgSection() {
        IpfsPublisher ipfsPublisher = mock(IpfsPublisher.class);
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("bafy-cid-1"));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(organisationPublicApi.findByOrganisationId("org-1")).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> creator(Optional.of(ipfsPublisher)).pullBlockchainTransaction("org-1", fixture()));
    }
}
