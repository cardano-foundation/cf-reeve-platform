package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;
import java.util.UUID;

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

        // A card carrying an attestation block must verify cryptographically — OOBI resolves, chain
        // valid, on-chain ATTEST binds this exact card — before it is trusted.
        Either<ProblemDetail, Void> attestationVerified = verifyAttestationIfPresent(card);
        if (attestationVerified.isLeft()) {
            return Either.left(attestationVerified.getLeft());
        }

        boolean isSelfImport = card.getSubject().subjectId().equals(securityHelper.getCurrentUserId());
        return Either.right(isSelfImport
                ? ImportCardResultView.ofOrgKey(importOwnKey(card, organisationId))
                : ImportCardResultView.ofAddressbookEntry(importContact(card, organisationId)));
    }

    /** Verifies a card's attestation block when present; a card with none is fine (trust-on-first-use). */
    private Either<ProblemDetail, Void> verifyAttestationIfPresent(KeyCardDto card) {
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
        return attestationVerifier.verify(securityHelper.getCurrentUserId(),
                new AttestationImportVerifier.CardAttestationClaim(attestation.oobi(), attestation.aid(),
                        attestation.credentialSaid(), attestation.schemaSaid(), attestation.txHash(),
                        attestation.credentialCesr(), cardDigest));
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
                key.setAttestationTxHash(attestation.txHash());
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
                entry.setAttestationTxHash(attestation.txHash());
                entry.setAttestationCredentialCesr(attestation.credentialCesr());
            }
        }
        return AddressbookService.toView(entryRepository.save(entry));
    }
}
