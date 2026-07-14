package org.cardanofoundation.lob.app.document_vault.service;

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

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B1: resolve -> validate -> dedupe -> auto-include the sender's own keys. The client
 * never assembles the recipient set itself.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipientResolutionService {

    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final KeyCardVerifier cardVerifier;

    public Either<ProblemDetail, List<RecipientKeyView>> resolve(ResolveRecipientsRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        String senderId = securityHelper.getCurrentUserId();

        Set<String> wanted = new HashSet<>(request.getRecipientAccountIds());
        wanted.add(senderId);

        // The kill switch, applied where it matters most (contract §2.8.5): a key whose issuer has been
        // de-trusted is not a wrap target. This is the ONE place a hostile injected key would otherwise
        // earn a slot in every future document addressed to its subject — resolve includes ALL of a
        // recipient's keys, so a substituted key rides along silently unless it is dropped here.
        // A PORTABLE key from a compromised issuer must be assumed known to the attacker (that issuer
        // minted it), so the sender's own keys are filtered too — no exception for the caller.
        List<VaultKeyEntity> keys = keyRepository.findByAccountIdInAndOrganisationId(wanted, organisationId)
                .stream()
                .filter(key -> cardVerifier.isTrustedIssuer(key.getIssuerId()))
                .toList();

        Set<String> accountsWithKeys = new HashSet<>(keys.stream().map(VaultKeyEntity::getAccountId).toList());
        List<String> missingRecipients = request.getRecipientAccountIds().stream()
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

        // The sender may narrow the wrap targets to a subset of their OWN keys (contract §5.4).
        // Empty/absent = all of them. Foreign key ids are rejected, never silently dropped: silently
        // dropping one would produce a document the sender cannot reopen on the device they chose.
        List<String> senderKeyIds = request.getSenderKeyIds();
        List<VaultKeyEntity> effectiveKeys = keys;
        if (senderKeyIds != null && !senderKeyIds.isEmpty()) {
            Set<String> ownKeyIds = keys.stream()
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
            effectiveKeys = keys.stream()
                    .filter(key -> !key.getAccountId().equals(senderId) || selected.contains(key.getId()))
                    .toList();
        }

        // dedupe by public key, first occurrence wins (stable order for the client)
        Map<String, RecipientKeyView> byPublicKey = new LinkedHashMap<>();
        for (VaultKeyEntity key : effectiveKeys) {
            byPublicKey.putIfAbsent(key.getPublicKey(), VaultKeyService.toRecipientView(key));
        }
        return Either.right(List.copyOf(byPublicKey.values()));
    }
}
