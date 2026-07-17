package org.cardanofoundation.lob.app.document_vault.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B1: resolve -> validate -> dedupe -> auto-include the sender's own keys. The client never
 * assembles the recipient set itself.
 *
 * Recipients come from two stores. Colleagues are accounts and may hold several keys — every one gets a
 * slot, or they can only reopen the document on one device. Addressbook contacts are the other way
 * round: one entry, one key, no account.
 *
 * Missing recipients are rejected, never silently dropped: a sender who asked for Bob and did not get him
 * would produce a document Bob cannot read, and would not find out until Bob said so.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipientResolutionService {

    private final VaultKeyRepository keyRepository;
    private final AddressbookEntryRepository entryRepository;
    private final KeycloakSecurityHelper securityHelper;

    public Either<ProblemDetail, List<RecipientKeyView>> resolve(ResolveRecipientsRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        String senderId = securityHelper.getCurrentUserId();

        // The sender is always a recipient of their own document, and always an account: you cannot send
        // as a contact, because nobody logs in as one.
        Set<String> wantedAccounts = new HashSet<>(request.accountIdsOrEmpty());
        wantedAccounts.add(senderId);

        List<VaultKeyEntity> orgKeys = keyRepository.findByAccountIdInAndOrganisationId(wantedAccounts, organisationId);

        Set<String> accountsWithKeys = orgKeys.stream()
                .map(VaultKeyEntity::getAccountId)
                .collect(Collectors.toSet());
        List<String> missingRecipients = request.accountIdsOrEmpty().stream()
                .distinct()
                .filter(accountId -> !accountsWithKeys.contains(accountId))
                .toList();
        if (!missingRecipients.isEmpty()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.RECIPIENT_KEY_MISSING,
                    "No key bound to organisation %s for account(s): %s"
                            .formatted(organisationId, String.join(", ", missingRecipients))));
        }
        if (!accountsWithKeys.contains(senderId)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.SENDER_KEY_MISSING,
                    "The sender has no key bound to organisation %s; enroll a key before encrypting."
                            .formatted(organisationId)));
        }

        // Scoped by organisation, so an entry id from another org's addressbook reads as unknown rather
        // than resolving across the tenancy boundary.
        List<String> wantedEntries = request.entryIdsOrEmpty().stream().distinct().toList();
        List<AddressbookEntryEntity> entries =
                entryRepository.findByIdInAndOrganisationId(wantedEntries, organisationId);
        Set<String> foundEntryIds = entries.stream()
                .map(AddressbookEntryEntity::getId)
                .collect(Collectors.toSet());
        List<String> missingEntries = wantedEntries.stream()
                .filter(entryId -> !foundEntryIds.contains(entryId))
                .toList();
        if (!missingEntries.isEmpty()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.RECIPIENT_ENTRY_MISSING,
                    "No addressbook entry in organisation %s for: %s"
                            .formatted(organisationId, String.join(", ", missingEntries))));
        }

        // The sender may narrow the wrap targets to a subset of their OWN keys (contract §5.4).
        // Empty/absent = all of them. Foreign key ids are rejected, never silently dropped: silently
        // dropping one would produce a document the sender cannot reopen on the device they chose.
        List<String> senderKeyIds = request.getSenderKeyIds();
        List<VaultKeyEntity> effectiveOrgKeys = orgKeys;
        if (senderKeyIds != null && !senderKeyIds.isEmpty()) {
            Set<String> ownKeyIds = orgKeys.stream()
                    .filter(key -> key.getAccountId().equals(senderId))
                    .map(VaultKeyEntity::getId)
                    .collect(Collectors.toSet());
            List<String> foreign = senderKeyIds.stream().distinct()
                    .filter(keyId -> !ownKeyIds.contains(keyId))
                    .toList();
            if (!foreign.isEmpty()) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SENDER_KEY_INVALID,
                        "Not a key of the current account in organisation %s: %s"
                                .formatted(organisationId, String.join(", ", foreign))));
            }
            Set<String> selected = Set.copyOf(senderKeyIds);
            effectiveOrgKeys = orgKeys.stream()
                    .filter(key -> !key.getAccountId().equals(senderId) || selected.contains(key.getId()))
                    .toList();
        }

        List<RecipientKeyView> resolved = new ArrayList<>(effectiveOrgKeys.stream()
                .map(VaultKeyService::toRecipientView)
                .toList());
        entries.stream().map(VaultKeyService::toRecipientView).forEach(resolved::add);

        // dedupe by public key, first occurrence wins (stable order for the client). A colleague and a
        // contact can hold the same key — someone imported a card for a person who also has a login —
        // and wrapping to it twice would just waste a slot.
        Map<String, RecipientKeyView> byPublicKey = new LinkedHashMap<>();
        for (RecipientKeyView view : resolved) {
            byPublicKey.putIfAbsent(view.publicKey(), view);
        }
        return Either.right(List.copyOf(byPublicKey.values()));
    }
}
