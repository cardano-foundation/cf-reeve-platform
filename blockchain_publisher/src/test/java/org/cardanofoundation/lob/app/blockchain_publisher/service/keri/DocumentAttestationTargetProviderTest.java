package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.CardanoNetwork;
import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.service.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * {@link DocumentAttestationTargetProvider#prepareDigest} builds its serialiser input via the same
 * {@code VaultDocumentService#toPublishCommand} + {@code DocumentConverter#convertToDbDetached} pair
 * the real publish/dispatch path uses (see the class javadoc's byte-identity argument), so this test
 * uses REAL {@link DocumentConverter}, {@link DocumentIpfsSerialiser} and {@link
 * DocumentMetadataSerialiser} instances (mirroring {@code DocumentL1TransactionCreatorTest}) rather
 * than mocking the mapping away — the whole point under test is that the frozen bytes are exactly
 * what dispatch would independently derive from the same {@link VaultDocumentEntity}.
 */
class DocumentAttestationTargetProviderTest {

    private static final ChainTip CHAIN_TIP = ChainTip.builder()
            .absoluteSlot(12345L)
            .blockHash("abc123")
            .epochNo(Optional.of(500))
            .network(CardanoNetwork.MAIN)
            .isSynced(true)
            .build();

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:15:30Z"), ZoneId.of("UTC"));

    private VaultDocumentService vaultDocumentService;
    private DocumentConverter documentConverter;
    private DocumentIpfsSerialiser documentIpfsSerialiser;
    private DocumentMetadataSerialiser documentMetadataSerialiser;
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private IpfsPublisher ipfsPublisher;
    private Cip170MetadataFactory cip170MetadataFactory;
    private DocumentAttestationFreezeRepository freezeRepository;
    private KeycloakSecurityHelper securityHelper;
    private OrganisationPublicApi organisationPublicApi;

    @BeforeEach
    void setUp() {
        vaultDocumentService = mock(VaultDocumentService.class);
        documentConverter = new DocumentConverter();
        documentIpfsSerialiser = new DocumentIpfsSerialiser(new ObjectMapper());
        organisationPublicApi = mock(OrganisationPublicApi.class);
        documentMetadataSerialiser = new DocumentMetadataSerialiser(organisationPublicApi, FIXED_CLOCK);
        blockchainReaderPublicApi = mock(BlockchainReaderPublicApiIF.class);
        ipfsPublisher = mock(IpfsPublisher.class);
        cip170MetadataFactory = new Cip170MetadataFactory();
        freezeRepository = mock(DocumentAttestationFreezeRepository.class);
        securityHelper = mock(KeycloakSecurityHelper.class);

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

    private DocumentAttestationTargetProvider provider() {
        return new DocumentAttestationTargetProvider(
                vaultDocumentService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                Optional.of(ipfsPublisher),
                cip170MetadataFactory,
                freezeRepository,
                securityHelper,
                FIXED_CLOCK);
    }

    private DocumentAttestationTargetProvider provider(Optional<IpfsPublisher> ipfs) {
        return new DocumentAttestationTargetProvider(
                vaultDocumentService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                ipfs,
                cip170MetadataFactory,
                freezeRepository,
                securityHelper,
                FIXED_CLOCK);
    }

    private static VaultDocumentEntity vaultDocumentFixture() {
        VaultDocumentEntity document = new VaultDocumentEntity();
        document.setId("doc-1");
        document.setOrganisationId("org-1");
        document.setStatus(VaultDocumentStatus.DRAFT);
        document.setEnvelopeVersion(1);
        document.setContentHash("a".repeat(64));
        document.setPlaintextHash("b".repeat(64));
        document.setCiphertext("not-really-encrypted".getBytes(StandardCharsets.UTF_8));
        document.setPayloadNonce("c".repeat(24));
        document.setCreatedByAccount("user-1");
        document.setSlots(List.of(new DocumentSlot("k-s", "recipient-ref", "d".repeat(64), "e".repeat(96))));
        return document;
    }

    /** Independently rebuilds the publisher-side {@link DocumentEntity} the provider must produce,
     *  via the exact same mapping the provider (and the real dispatch path) uses. */
    private DocumentEntity expectedPublisherEntity(VaultDocumentEntity vaultDocument) {
        DocumentPublishCommand command = VaultDocumentService.toPublishCommand(vaultDocument);
        return new DocumentConverter().convertToDbDetached(command);
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- targetType ---

    @Test
    void targetTypeIsDocument() {
        assertThat(provider().targetType()).isEqualTo("DOCUMENT");
    }

    // --- authorize ---

    @Test
    void authorizeDelegatesToVaultDocumentServiceLoadForAttestation() {
        VaultDocumentEntity document = vaultDocumentFixture();
        when(vaultDocumentService.loadForAttestation("doc-1", "user-1")).thenReturn(Either.right(document));

        Optional<ProblemDetail> result = provider().authorize("doc-1", "user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void authorizeRejectsNonDraftDocument() {
        ProblemDetail alreadyPublished = VaultProblems.conflict(VaultProblems.ALREADY_PUBLISHED, "Document doc-1 is already published.");
        when(vaultDocumentService.loadForAttestation("doc-1", "user-1")).thenReturn(Either.left(alreadyPublished));

        Optional<ProblemDetail> result = provider().authorize("doc-1", "user-1");

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo(VaultProblems.ALREADY_PUBLISHED);
    }

    // --- prepareDigest: happy path ---

    @Test
    void prepareDigestFreezesEnvelopeAndReturnsTheDigest() throws Exception {
        VaultDocumentEntity vaultDocument = vaultDocumentFixture();
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.empty());
        when(vaultDocumentService.loadForAttestation("doc-1", "user-1")).thenReturn(Either.right(vaultDocument));
        when(securityHelper.getCurrentUserId()).thenReturn("user-1");
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("bafy-cid-1"));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));

        Either<ProblemDetail, AttestationDigest> result = provider().prepareDigest("doc-1", "cer-1");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().metadataLabel()).isEqualTo("1447");
        assertThat(result.get().digestQb64()).startsWith("E");

        ArgumentCaptor<DocumentAttestationFreezeEntity> captor = ArgumentCaptor.forClass(DocumentAttestationFreezeEntity.class);
        verify(freezeRepository).save(captor.capture());
        DocumentAttestationFreezeEntity saved = captor.getValue();

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getDocumentId()).isEqualTo("doc-1");
        assertThat(saved.getCeremonyId()).isEqualTo("cer-1");
        assertThat(saved.getIpfsCid()).isEqualTo("bafy-cid-1");
        assertThat(saved.getMetadataCreationSlot()).isEqualTo(12345L);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        assertThat(saved.getDigestQb64()).isEqualTo(result.get().digestQb64());

        // --- byte-identity: same mapping (VaultDocumentService.toPublishCommand + DocumentConverter),
        // same envelope, same metadata map, same frozen bytes, same digest as dispatch would produce ---
        DocumentEntity expectedEntity = expectedPublisherEntity(vaultDocument);
        String expectedEnvelope = documentIpfsSerialiser.serialise(expectedEntity);
        assertThat(saved.getEnvelopeSha256()).isEqualTo(sha256Hex(expectedEnvelope));

        MetadataMap expectedMap = documentMetadataSerialiser.serialiseToMetadataMap(expectedEntity, "bafy-cid-1", 12345L);
        byte[] expectedFrozenBytes = CborSerializationUtil.serialize(expectedMap.getMap());
        assertThat(saved.getFrozenMetadataCbor()).isEqualTo(expectedFrozenBytes);

        String expectedDigest = cip170MetadataFactory.digestOf(expectedMap);
        assertThat(saved.getDigestQb64()).isEqualTo(expectedDigest);
    }

    @Test
    void prepareDigestIsIdempotentPerDocumentAndCeremony() {
        DocumentAttestationFreezeEntity existing = new DocumentAttestationFreezeEntity();
        existing.setDigestQb64("Ealready-frozen-digest");
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.of(existing));

        Either<ProblemDetail, AttestationDigest> result = provider().prepareDigest("doc-1", "cer-1");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().digestQb64()).isEqualTo("Ealready-frozen-digest");
        assertThat(result.get().metadataLabel()).isEqualTo("1447");

        verifyNoInteractions(vaultDocumentService, ipfsPublisher, blockchainReaderPublicApi);
        verify(freezeRepository, never()).save(any());
    }

    /**
     * Coordinator review finding 2 (Task 13 fix round 1): the find-then-save in {@code
     * prepareDigest} is check-then-act, not atomic — a concurrent caller can win the unique
     * {@code (document_id, ceremony_id)} constraint race between this call's own (empty) existence
     * check and its {@code save}. This simulates exactly that: the first {@code
     * findByDocumentIdAndCeremonyId} call (the idempotency pre-check) finds nothing, {@code save}
     * throws {@link DataIntegrityViolationException} (the concurrent winner's row landed first), and
     * the provider must re-read and return THAT row's digest rather than propagate the exception.
     */
    @Test
    void prepareDigestRecoversFromAConcurrentUniqueConstraintRaceByReturningTheWinnersDigest() {
        DocumentAttestationFreezeEntity winner = new DocumentAttestationFreezeEntity();
        winner.setDigestQb64("Ewinner-digest-from-concurrent-caller");
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1"))
                .thenReturn(Optional.empty())  // this call's own idempotency pre-check: nothing yet
                .thenReturn(Optional.of(winner)); // re-read after losing the save race: the winner's row
        when(vaultDocumentService.loadForAttestation("doc-1", "user-1")).thenReturn(Either.right(vaultDocumentFixture()));
        when(securityHelper.getCurrentUserId()).thenReturn("user-1");
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.right("bafy-cid-1"));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(CHAIN_TIP));
        when(freezeRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Either<ProblemDetail, AttestationDigest> result = provider().prepareDigest("doc-1", "cer-1");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().digestQb64()).isEqualTo("Ewinner-digest-from-concurrent-caller");
        assertThat(result.get().metadataLabel()).isEqualTo("1447");
        verify(freezeRepository, org.mockito.Mockito.times(2)).findByDocumentIdAndCeremonyId("doc-1", "cer-1");
    }

    @Test
    void prepareDigestPropagatesNonDraftRejectionWithoutTouchingIpfs() {
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.empty());
        when(securityHelper.getCurrentUserId()).thenReturn("user-1");
        ProblemDetail alreadyPublished = VaultProblems.conflict(VaultProblems.ALREADY_PUBLISHED, "Document doc-1 is already published.");
        when(vaultDocumentService.loadForAttestation("doc-1", "user-1")).thenReturn(Either.left(alreadyPublished));

        Either<ProblemDetail, AttestationDigest> result = provider().prepareDigest("doc-1", "cer-1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.ALREADY_PUBLISHED);
        verifyNoInteractions(ipfsPublisher, blockchainReaderPublicApi);
        verify(freezeRepository, never()).save(any());
    }

    @Test
    void prepareDigestReturnsServiceUnavailableWhenIpfsNotConfigured() {
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.empty());

        Either<ProblemDetail, AttestationDigest> result = provider(Optional.empty()).prepareDigest("doc-1", "cer-1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(result.getLeft().getTitle()).isEqualTo(VaultProblems.DOCUMENT_PUBLISHING_UNAVAILABLE);
        verifyNoInteractions(vaultDocumentService, blockchainReaderPublicApi);
    }

    @Test
    void prepareDigestPropagatesIpfsFailureWithoutBuildingAFreeze() {
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.empty());
        when(vaultDocumentService.loadForAttestation("doc-1", "user-1")).thenReturn(Either.right(vaultDocumentFixture()));
        when(securityHelper.getCurrentUserId()).thenReturn("user-1");
        ProblemDetail ipfsError = ProblemDetail.forStatus(500);
        ipfsError.setTitle("IPFS_UPLOAD_ERROR");
        when(ipfsPublisher.publish(anyString())).thenReturn(Either.left(ipfsError));

        Either<ProblemDetail, AttestationDigest> result = provider().prepareDigest("doc-1", "cer-1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("IPFS_UPLOAD_ERROR");
        verifyNoInteractions(blockchainReaderPublicApi);
        verify(freezeRepository, never()).save(any());
    }

}
