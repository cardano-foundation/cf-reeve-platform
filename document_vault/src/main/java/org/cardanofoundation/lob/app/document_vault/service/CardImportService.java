package org.cardanofoundation.lob.app.document_vault.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.AddressbookEntryView;
import org.cardanofoundation.lob.app.document_vault.domain.view.ImportCardResultView;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationImportVerifier;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialVerificationService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Imports a permissionless key card — how a new recipient enters an addressbook, including a holder
 * with no Reeve login whose card was minted outside the platform. A card is self-asserted, with no
 * issuer and no signature, so any org member may import any well-formed card and the sender is
 * responsible for verifying the recipient's public key out-of-band (trust-on-first-use).
 *
 * <p>The organisation comes from the request, never the card: the caller must be a member of it, and
 * the row is stamped with it. The organisation the card names is the holder's own, free-form and kept
 * only as provenance.
 *
 * <p>The destination store is the only branch here, and it keys on the caller's Keycloak subject:
 * <pre>
 *   subject is the caller  →  organisation key   (their own key; appears in /keys/me)
 *   anyone else            →  addressbook entry  (a contact; no account, cannot log in)
 * </pre>
 * Never on the card's {@code subjectType}, which is unsigned and would let a forged card choose its
 * own destination. Routing to separate tables also makes a card's claim about someone else's account
 * unrepresentable rather than merely rejected: an addressbook entry has no account id to collide with.
 */
@Service
@RequiredArgsConstructor
public class CardImportService {

    private final VaultKeyRepository keyRepository;
    private final AddressbookEntryRepository entryRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final KeyCardVerifier verifier;
    private final CardAttestationDigestFactory attestationDigestFactory;
    // Present only when keri_attestation is enabled. A card claiming an attestation that cannot be
    // verified is rejected, never imported as if it were unattested.
    private final ObjectProvider<AttestationImportVerifier> attestationVerifierProvider;
    // Where the verdict is recorded. keri_attestation owns it; this module only asks for it to be
    // written and later reads it back for display.
    private final ObjectProvider<CredentialVerificationService> verificationServiceProvider;

    @Transactional
    public Either<ProblemDetail, ImportCardResultView> importCard(ImportCardRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }

        Either<ProblemDetail, KeyCardDto> verified = verifier.verify(request.getCard());
        if (verified.isLeft()) {
            return Either.left(verified.getLeft());
        }
        KeyCardDto card = verified.get();

        // A card carrying an attestation block must verify cryptographically — OOBI resolves, the
        // credential chain is trusted, and the attesting AID sealed this exact card in its KEL — before
        // it is trusted.
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> attestationVerified =
                verifyAttestationIfPresent(card);
        if (attestationVerified.isLeft()) {
            return Either.left(attestationVerified.getLeft());
        }

        boolean isSelfImport = card.getSubject().subjectId().equals(securityHelper.getCurrentUserId());

        Either<ProblemDetail, Void> mutable = checkAttestedFieldsNotRewritten(card, organisationId, isSelfImport);
        if (mutable.isLeft()) {
            return Either.left(mutable.getLeft());
        }

        recordVerdictIfVerified(card, organisationId, attestationVerified.get());

        return Either.right(isSelfImport
                ? ImportCardResultView.ofOrgKey(importOwnKey(card, organisationId))
                : ImportCardResultView.ofAddressbookEntry(importContact(card, organisationId)));
    }

    /**
     * Records the verdict for a card whose attestation verified.
     *
     * <p>Only ever writes a passing verdict. A card with no attestation leaves any existing verdict
     * untouched rather than clearing it: someone re-importing a stripped copy of a colleague's card
     * must not be able to quietly remove the badge from it.
     */
    private void recordVerdictIfVerified(KeyCardDto card,  String organisationId,
            @Nullable CredentialChainValidator.ValidatedCredential credential) {
        if (credential == null) {
            return;
        }
        CredentialVerificationService verificationService = verificationServiceProvider.getIfAvailable();
        if (verificationService == null) {
            return;
        }
        KeyCardDto.CardAttestation attestation = card.getAttestation();
        verificationService.recordVerified(organisationId, card.getKey().publicKey(), attestation.aid(),
                credential, attestation.kelSequence(), attestation.kelEventSaid(),
                credential.policyFingerprint());
    }

    /**
     * Refuses a re-import that would rewrite an attested row's identity fields with values no
     * attestation covers.
     *
     * <p>The issuer's signed digest covers {@code displayName}, {@code email} and {@code label}, but
     * attestation provenance is written once at creation and never revisited. Without this check, the
     * two facts combine into an attack: import a genuine attested card, then re-import an unattested
     * card carrying the same public key and attacker-chosen name and e-mail. Every field a reader
     * actually looks at would change while the row kept the verified anchor that vouched for the old
     * values.
     *
     * <p>Only cards that cannot produce a matching attestation are refused, and only when they would
     * actually change something — a re-import that changes nothing stays the ordinary no-op it looks
     * like. A card carrying its own verified attestation has already been checked against the digest
     * over exactly these fields, so it is free to update them.
     */
    private Either<ProblemDetail, Void> checkAttestedFieldsNotRewritten(KeyCardDto card, String organisationId,
            boolean isSelfImport) {
        if (card.getAttestation() != null) {
            return Either.right(null);
        }
        String publicKey = card.getKey().publicKey();
        if (!isAttested(organisationId, publicKey, isSelfImport)) {
            return Either.right(null);
        }
        if (isSelfImport) {
            return keyRepository
                    .findByAccountIdAndOrganisationIdAndPublicKey(securityHelper.getCurrentUserId(),
                            organisationId, publicKey)
                    .filter(existing -> differs(existing.getLabel(), card.getKey().label()))
                    .<Either<ProblemDetail, Void>>map(existing -> Either.left(attestedFieldsImmutable("label")))
                    .orElseGet(() -> Either.right(null));
        }
        return entryRepository.findByOrganisationIdAndPublicKey(organisationId, publicKey)
                .map(existing -> changedContactFields(existing, card))
                .filter(changed -> !changed.isEmpty())
                .<Either<ProblemDetail, Void>>map(changed -> Either.left(attestedFieldsImmutable(
                        String.join(", ", changed))))
                .orElseGet(() -> Either.right(null));
    }

    /**
     * @return whether anything vouches for this card's identity fields — either the row's own
     *         provenance columns or a recorded verification verdict.
     *
     * <p>Both, not just the columns. Provenance is written once at row CREATION, so a card first
     * imported unattested and later re-imported with a verified attestation earns a verdict while its
     * {@code attestationAid} stays null. Checking only the column would then read that row as
     * unattested and let the next unattested import rewrite the very fields the verdict covers.
     */
    private boolean isAttested(String organisationId, String publicKey, boolean isSelfImport) {
        boolean provenanceOnRow = isSelfImport
                ? keyRepository.findByAccountIdAndOrganisationIdAndPublicKey(securityHelper.getCurrentUserId(),
                        organisationId, publicKey).filter(k -> k.getAttestationAid() != null).isPresent()
                : entryRepository.findByOrganisationIdAndPublicKey(organisationId, publicKey)
                        .filter(e -> e.getAttestationAid() != null).isPresent();
        if (provenanceOnRow) {
            return true;
        }
        CredentialVerificationService verificationService = verificationServiceProvider.getIfAvailable();
        return verificationService != null
                && verificationService.find(organisationId, publicKey).isPresent();
    }

    private static List<String> changedContactFields(AddressbookEntryEntity existing, KeyCardDto card) {
        List<String> changed = new ArrayList<>();
        if (differs(existing.getDisplayName(), card.getSubject().displayName())) {
            changed.add("displayName");
        }
        if (differs(existing.getEmail(), card.getSubject().email())) {
            changed.add("email");
        }
        if (differs(existing.getDescription(), card.getKey().label())) {
            changed.add("label");
        }
        return changed;
    }

    private static boolean differs(String stored, String incoming) {
        return !Objects.equals(stored, incoming);
    }

    private static ProblemDetail attestedFieldsImmutable(String fields) {
        return VaultProblems.conflict(VaultProblems.ATTESTED_CARD_FIELDS_IMMUTABLE,
                ("This public key is already stored with a verified attestation covering %s. An unattested "
                        + "card cannot change those values — re-export the card from its issuer with a current "
                        + "attestation.").formatted(fields));
    }

    /**
     * Verifies a card's attestation block when present; a card with none is fine (trust-on-first-use).
     *
     * @return the validated credential when the card carried a verifiable attestation, or
     *         {@code Either.right(null)} when it carried none at all — an unattested card is imported,
     *         it simply earns no verdict.
     */
    private Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> verifyAttestationIfPresent(
            KeyCardDto card) {
        KeyCardDto.CardAttestation attestation = card.getAttestation();
        if (attestation == null) {
            return Either.right(null);
        }
        AttestationImportVerifier attestationVerifier = attestationVerifierProvider.getIfAvailable();
        if (attestationVerifier == null) {
            return Either.left(VaultProblems.serviceUnavailable(AttestationImportVerifier.CARD_ATTESTATION_UNVERIFIABLE,
                    "This card claims a Veridian attestation but attestation verification is not available "
                            + "(keri_attestation module disabled)."));
        }
        String cardDigest = attestationDigestFactory.digestOf(card);
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> verified = attestationVerifier.verify(
                securityHelper.getCurrentUserId(),
                new AttestationImportVerifier.CardAttestationClaim(attestation.oobi(), attestation.aid(),
                        attestation.credentialSaid(), attestation.schemaSaid(), attestation.kelSequence(),
                        attestation.kelEventSaid(), attestation.metadataLabel(), attestation.cardDigest(),
                        attestation.payloadSaid(), attestation.credentialCesr(), cardDigest));
        return verified;
    }

    /**
     * A card about the caller: their own key, bound to their Keycloak account. Idempotent on
     * (account, org, publicKey) — the table's UNIQUE constraint.
     */
    private VaultKeyView importOwnKey(KeyCardDto card, String organisationId) {
        String accountId = securityHelper.getCurrentUserId();
        Optional<VaultKeyEntity> existing = keyRepository.findByAccountIdAndOrganisationIdAndPublicKey(
                accountId, organisationId, card.getKey().publicKey());

        VaultKeyEntity key;
        if (existing.isPresent()) {
            // Re-import refreshes only the label. Provenance is set once at creation: a PORTABLE key
            // must never silently upgrade to PASSKEY because a card turned up.
            key = existing.get();
            key.setLabel(card.getKey().label());
        } else {
            key = new VaultKeyEntity();
            key.setId(UUID.randomUUID().toString());
            key.setAccountId(accountId);
            key.setOrganisationId(organisationId);
            key.setPublicKey(card.getKey().publicKey());
            // The Keycloak name from the caller's own token, not the card's displayName: this is their
            // account, and the token is the only part of this that is not self-asserted.
            key.setAccountName(securityHelper.getCurrentUser());
            key.setLabel(card.getKey().label());
            key.setOrigin(KeyOrigin.INDEXER_ISSUED);
            // Self-asserted on the card — the backend cannot check how a key was born and does not
            // pretend to. It stores the claim and shows it to everyone who picks the key.
            key.setAssurance(card.getKey().assurance());
            // Attestation provenance, set once at creation like origin and assurance above. Verified
            // in verifyAttestationIfPresent before reaching here; an absent block leaves these null.
            if (card.getAttestation() != null) {
                KeyCardDto.CardAttestation attestation = card.getAttestation();
                key.setAttestationOobi(attestation.oobi());
                key.setAttestationAid(attestation.aid());
                key.setAttestationCredentialSaid(attestation.credentialSaid());
                key.setAttestationSchemaSaid(attestation.schemaSaid());
                key.setAttestationKelSequence(attestation.kelSequence());
                key.setAttestationKelEventSaid(attestation.kelEventSaid());
                key.setAttestationMetadataLabel(attestation.metadataLabel());
                key.setAttestationCardDigest(attestation.cardDigest());
                key.setAttestationPayloadSaid(attestation.payloadSaid());
                key.setAttestationCredentialCesr(attestation.credentialCesr());
            }
        }
        return VaultKeyService.toView(keyRepository.save(key));
    }

    /**
     * A card about anyone else: a contact. Idempotent on (org, publicKey), since re-adding a recipient
     * is normal user behaviour rather than an error.
     */
    private AddressbookEntryView importContact(KeyCardDto card, String organisationId) {
        Optional<AddressbookEntryEntity> existing = entryRepository
                .findByOrganisationIdAndPublicKey(organisationId, card.getKey().publicKey());

        AddressbookEntryEntity entry;
        if (existing.isPresent()) {
            // Only what the card says about the person refreshes. homeOrganisationId and assurance
            // record what the first card claimed; letting a later import rewrite an origin a sender
            // already verified out-of-band would defeat the point of showing it.
            entry = existing.get();
            entry.setDisplayName(card.getSubject().displayName());
            entry.setEmail(card.getSubject().email());
            entry.setDescription(card.getKey().label());
        } else {
            entry = new AddressbookEntryEntity();
            entry.setId(UUID.randomUUID().toString());
            entry.setOrganisationId(organisationId);
            entry.setDisplayName(card.getSubject().displayName());
            entry.setEmail(card.getSubject().email());
            entry.setDescription(card.getKey().label());
            entry.setPublicKey(card.getKey().publicKey());
            entry.setAssurance(card.getKey().assurance());
            // The holder's own organisation, free-form and unverified ("Privat"). Provenance shown to
            // senders, never matched against the organisation above.
            entry.setHomeOrganisationId(card.getSubject().organisationId());
            // Attestation provenance, set once at creation like homeOrganisationId and assurance
            // above. An absent block leaves these null.
            if (card.getAttestation() != null) {
                KeyCardDto.CardAttestation attestation = card.getAttestation();
                entry.setAttestationOobi(attestation.oobi());
                entry.setAttestationAid(attestation.aid());
                entry.setAttestationCredentialSaid(attestation.credentialSaid());
                entry.setAttestationSchemaSaid(attestation.schemaSaid());
                entry.setAttestationKelSequence(attestation.kelSequence());
                entry.setAttestationKelEventSaid(attestation.kelEventSaid());
                entry.setAttestationMetadataLabel(attestation.metadataLabel());
                entry.setAttestationCardDigest(attestation.cardDigest());
                entry.setAttestationPayloadSaid(attestation.payloadSaid());
                entry.setAttestationCredentialCesr(attestation.credentialCesr());
            }
        }
        return AddressbookService.toView(entryRepository.save(entry));
    }
}
