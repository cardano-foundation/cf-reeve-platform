package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.document_vault.service.VaultProblems;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;

/**
 * {@link DocumentAttestationFreezeGuard} is document_vault's {@code AttestationFreezeGuard} port,
 * implemented here (design §5.1 step 2 / §5.2, Task 14). Uses REAL {@link DocumentConverter} and
 * {@link DocumentIpfsSerialiser} instances (mirroring {@code DocumentAttestationTargetProviderTest},
 * Task 13) rather than mocking the mapping away — the whole point under test is that the recomputed
 * fingerprint is exactly what Task 13's provider would have frozen from the same
 * {@link VaultDocumentEntity}.
 */
class DocumentAttestationFreezeGuardTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private final DocumentAttestationFreezeRepository freezeRepository = mock(DocumentAttestationFreezeRepository.class);
    private final DocumentConverter documentConverter = new DocumentConverter();
    private final DocumentIpfsSerialiser documentIpfsSerialiser = new DocumentIpfsSerialiser(new ObjectMapper());

    private DocumentAttestationFreezeGuard guard(Duration freezeMaxAge) {
        return new DocumentAttestationFreezeGuard(freezeRepository, documentConverter, documentIpfsSerialiser,
                properties(freezeMaxAge), FIXED_CLOCK);
    }

    private static KeriAttestationProperties properties(Duration freezeMaxAge) {
        return new KeriAttestationProperties(
                true,
                new KeriAttestationProperties.Keria("https://keria.example", "https://keria.example/boot", "bran-secret"),
                "reeve-agent",
                new KeriAttestationProperties.CredentialPolicy(List.of(), List.of(), null),
                Duration.parse("PT1H"),
                freezeMaxAge,
                Duration.parse("PT3M"),
                Duration.parse("PT1.5S"),
                3,
                new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"),
                Duration.parse("PT30M"),
                Duration.parse("PT2S"),
                Duration.parse("PT3S"),
                Duration.parse("PT2M"),
                new KeriAttestationProperties.Executor(4, 2));
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

    /** Recomputes the same fingerprint the guard (and Task 13's provider) would derive, independently,
     *  from the same document — the exact production chain, called a second time by the test. */
    private static String expectedEnvelopeSha256(VaultDocumentEntity vaultDocument) {
        DocumentPublishCommand command = VaultDocumentService.toPublishCommand(vaultDocument);
        DocumentEntity entity = new DocumentConverter().convertToDbDetached(command);
        String envelopeJson = new DocumentIpfsSerialiser(new ObjectMapper()).serialise(entity);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(envelopeJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentAttestationFreezeEntity freeze(String envelopeSha256, LocalDateTime createdAt) {
        DocumentAttestationFreezeEntity freeze = new DocumentAttestationFreezeEntity();
        freeze.setId("freeze-1");
        freeze.setDocumentId("doc-1");
        freeze.setCeremonyId("cer-1");
        freeze.setIpfsCid("bafy-cid-1");
        freeze.setFrozenMetadataCbor(new byte[] { 1, 2, 3 });
        freeze.setDigestQb64("Edigest");
        freeze.setMetadataCreationSlot(12345L);
        freeze.setEnvelopeSha256(envelopeSha256);
        freeze.setCreatedAt(createdAt);
        return freeze;
    }

    @Test
    void verifyFreshnessPassesWhenFreezeIsFreshAndEnvelopeUnchanged() {
        VaultDocumentEntity document = vaultDocumentFixture();
        DocumentAttestationFreezeEntity freeze = freeze(expectedEnvelopeSha256(document), LocalDateTime.now(FIXED_CLOCK).minusHours(1));
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.of(freeze));

        Optional<ProblemDetail> result = guard(Duration.parse("PT24H")).verifyFreshness(document, "cer-1");

        assertThat(result).isEmpty();
    }

    @Test
    void verifyFreshnessReturnsFreezeMissingWhenNoFreezeRowExists() {
        VaultDocumentEntity document = vaultDocumentFixture();
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.empty());

        Optional<ProblemDetail> result = guard(Duration.parse("PT24H")).verifyFreshness(document, "cer-1");

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo(VaultProblems.ATTESTATION_FREEZE_MISSING);
        assertThat(result.get().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void verifyFreshnessReturnsContentChangedWhenEnvelopeDriftedSinceAttest() {
        VaultDocumentEntity document = vaultDocumentFixture();
        // A freeze recorded a fingerprint from a DIFFERENT envelope than the document's current one.
        DocumentAttestationFreezeEntity freeze = freeze("f".repeat(64), LocalDateTime.now(FIXED_CLOCK).minusHours(1));
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.of(freeze));

        Optional<ProblemDetail> result = guard(Duration.parse("PT24H")).verifyFreshness(document, "cer-1");

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo(VaultProblems.ATTESTED_CONTENT_CHANGED);
        assertThat(result.get().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    void verifyFreshnessReturnsMetadataMismatchWhenFreezeIsOlderThanConfiguredMaxAge() {
        VaultDocumentEntity document = vaultDocumentFixture();
        // Envelope fingerprint still matches - only the age is the problem.
        DocumentAttestationFreezeEntity freeze = freeze(expectedEnvelopeSha256(document), LocalDateTime.now(FIXED_CLOCK).minusHours(25));
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.of(freeze));

        Optional<ProblemDetail> result = guard(Duration.parse("PT24H")).verifyFreshness(document, "cer-1");

        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo(VaultProblems.ATTESTED_METADATA_MISMATCH);
        assertThat(result.get().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(result.get().getDetail()).contains("PT25H", "PT24H");
    }

    @Test
    void verifyFreshnessAtExactlyTheMaxAgeBoundaryStillPasses() {
        VaultDocumentEntity document = vaultDocumentFixture();
        DocumentAttestationFreezeEntity freeze = freeze(expectedEnvelopeSha256(document), LocalDateTime.now(FIXED_CLOCK).minusHours(24));
        when(freezeRepository.findByDocumentIdAndCeremonyId("doc-1", "cer-1")).thenReturn(Optional.of(freeze));

        Optional<ProblemDetail> result = guard(Duration.parse("PT24H")).verifyFreshness(document, "cer-1");

        assertThat(result).isEmpty();
    }

}
