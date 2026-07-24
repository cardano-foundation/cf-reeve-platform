package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

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
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B6 — import a permissionless key card (contract §2.8, 5.13).
 *
 * This is how a new recipient enters an addressbook, including a holder with no Reeve login whose card
 * was minted entirely outside the platform. The card is self-asserted: there is no issuer and no
 * signature, so any org member may import any well-formed card, and the SENDER is responsible for
 * verifying the recipient's public key out-of-band before encrypting to it (trust-on-first-use).
 *
 * <h2>Which organisation</h2>
 * Decided by the REQUEST, never the card: the caller must be a member of the request's organisation, and
 * the row is stamped with it. The organisation the card names is the HOLDER's own — free-form, possibly
 * "Privat" — and is kept only as provenance.
 *
 * <h2>Which store</h2>
 * Decided by the card's subject, and this is the only branch here:
 * <pre>
 *   subject is the caller  →  organisation key   (their own key; appears in /keys/me)
 *   anyone else            →  addressbook entry  (a contact; no account, cannot log in)
 * </pre>
 * It keys on the caller's Keycloak sub and never on the card's {@code subjectType}: the card is unsigned,
 * so {@code subjectType} is only what the importer typed, and branching on it would let a forged card
 * choose its own destination. The caller's sub is the one value here anchored to the JWT.
 *
 * Routing to separate tables — rather than tagging rows in one — is what makes a card's claim about
 * someone else's account unrepresentable instead of merely rejected: an addressbook entry has no account
 * id to collide with.
 *
 * The endpoint is {@code /addressbook/import}, so a card about the caller is the one case where the path
 * does not describe the outcome. The result states its destination rather than leaving it to be inferred.
 */
@Service
@RequiredArgsConstructor
public class CardImportService {

    private final VaultKeyRepository keyRepository;
    private final AddressbookEntryRepository entryRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final KeyCardVerifier verifier;

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

        boolean isSelfImport = card.getSubject().subjectId().equals(securityHelper.getCurrentUserId());
        return Either.right(isSelfImport
                ? ImportCardResultView.ofOrgKey(importOwnKey(card, organisationId))
                : ImportCardResultView.ofAddressbookEntry(importContact(card, organisationId)));
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
            // Re-import refreshes only the label (contract §2.8.5). Provenance — origin, assurance — is
            // set once at creation: a PORTABLE key must never silently upgrade to PASSKEY, and a
            // SELF_ENROLLED row must never flip to INDEXER_ISSUED, just because a card turned up.
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
            // Attestation provenance (design doc "The card-format contract", Part B/B1): set once at
            // creation, same as origin/assurance above. B2 will verify these against KERIA/on-chain;
            // for now the card's claim is simply stored. Absent block -> all five stay NULL.
            if (card.getAttestation() != null) {
                KeyCardDto.CardAttestation attestation = card.getAttestation();
                key.setAttestationOobi(attestation.oobi());
                key.setAttestationAid(attestation.aid());
                key.setAttestationCredentialSaid(attestation.credentialSaid());
                key.setAttestationSchemaSaid(attestation.schemaSaid());
                key.setAttestationTxHash(attestation.txHash());
            }
        }
        return VaultKeyService.toView(keyRepository.save(key));
    }

    /**
     * A card about anyone else: a contact. Idempotent on (org, publicKey) — re-adding a recipient is
     * normal user behaviour, not an error (contract §2.8.5).
     */
    private AddressbookEntryView importContact(KeyCardDto card, String organisationId) {
        Optional<AddressbookEntryEntity> existing = entryRepository
                .findByOrganisationIdAndPublicKey(organisationId, card.getKey().publicKey());

        AddressbookEntryEntity entry;
        if (existing.isPresent()) {
            // Only what the card says about the person refreshes. homeOrganisationId and assurance are
            // provenance: they record what the card claimed when this contact first entered the
            // addressbook, and letting a later import rewrite the origin a sender already verified
            // out-of-band would defeat the point of showing it.
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
            // Attestation provenance (design doc "The card-format contract", Part B/B1): set once at
            // creation, same as homeOrganisationId/assurance above. B2 will verify these against
            // KERIA/on-chain; for now the card's claim is simply stored. Absent block -> all five stay
            // NULL.
            if (card.getAttestation() != null) {
                KeyCardDto.CardAttestation attestation = card.getAttestation();
                entry.setAttestationOobi(attestation.oobi());
                entry.setAttestationAid(attestation.aid());
                entry.setAttestationCredentialSaid(attestation.credentialSaid());
                entry.setAttestationSchemaSaid(attestation.schemaSaid());
                entry.setAttestationTxHash(attestation.txHash());
            }
        }
        return AddressbookService.toView(entryRepository.save(entry));
    }
}
