package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B6 — import a permissionless key card (contract §2.8, 5.13).
 *
 * This is how a NEW RECIPIENT enters an addressbook. The card is self-asserted: there is no issuer
 * and no signature, so any org member may import any well-formed card, and the SENDER is responsible
 * for verifying the recipient's public key out-of-band before encrypting to it (trust-on-first-use).
 *
 * The card's SUBJECT decides ownership, never the caller: a card about Bob creates Bob's entry (an
 * addressbook contact), a card about the caller lands in their own /keys/me. No branch is needed —
 * account_id is simply the subject id in both cases.
 */
@Service
@RequiredArgsConstructor
public class CardImportService {

    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final KeyCardVerifier verifier;

    @Transactional
    public Either<ProblemDetail, VaultKeyView> importCard(ImportCardRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }

        Either<ProblemDetail, KeyCardDto> verified = verifier.verify(request.getCard(), organisationId);
        if (verified.isLeft()) {
            return Either.left(verified.getLeft());
        }
        KeyCardDto card = verified.get();
        String holderId = card.getSubject().subjectId();

        // Idempotent on (account, org, publicKey) — the table's UNIQUE constraint. Re-importing a
        // recipient is normal user behaviour; it refreshes ONLY their label/e-mail from the card
        // (contract §2.8.5). Provenance fields (origin, assurance, external) are set once, at
        // creation, and never overwritten by a later re-import.
        Optional<VaultKeyEntity> existing = keyRepository
                .findByAccountIdAndOrganisationIdAndPublicKey(holderId, organisationId, card.getKey().publicKey());

        VaultKeyEntity key;
        if (existing.isPresent()) {
            key = existing.get();
            key.setLabel(card.getKey().label());
            key.setEmail(card.getSubject().email());
        } else {
            key = new VaultKeyEntity();
            key.setId(UUID.randomUUID().toString());
            key.setAccountId(holderId);
            key.setOrganisationId(organisationId);
            key.setPublicKey(card.getKey().publicKey());
            key.setAccountName(card.getSubject().displayName());
            key.setEmail(card.getSubject().email());
            key.setLabel(card.getKey().label());
            key.setOrigin(KeyOrigin.INDEXER_ISSUED);
            // The tier is self-asserted on the card — the backend cannot check how a key was born, and
            // does not pretend to. It stores what the card claims and shows it to every user who picks
            // the key.
            key.setAssurance(card.getKey().assurance());
            key.setExternal(card.getSubject().subjectType() == CardSubjectType.EXTERNAL);
        }

        return Either.right(VaultKeyService.toView(keyRepository.save(key)));
    }
}
