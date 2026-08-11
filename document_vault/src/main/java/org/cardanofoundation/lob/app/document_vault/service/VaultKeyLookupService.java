package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.document_vault.domain.KeyRef;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;

/**
 * Resolves a document slot's {@code keyId} without the caller needing to know which store it came from.
 *
 * A slot may wrap to an organisation key or to an addressbook contact. Both id spaces are UUIDs, so a
 * merged lookup is unambiguous. This exists because a foreign key cannot point at two tables: the slot's
 * FK was dropped in the split, and this is what replaces it.
 *
 * Losing that constraint costs little. Hibernate never navigated it — {@code DocumentSlot.keyId} is a
 * plain column on an embeddable — and the check it implied still runs in {@link VaultDocumentService},
 * which verifies each slot key's organisation on upload. The FK was also actively harmful: with no
 * ON DELETE clause it rejected the deletion of any key a document had ever been wrapped to, contradicting
 * the delete contract. A slot keeps its own wrapped DEK and stays decryptable whatever happens to the
 * directory row, so a dangling keyId is a label going stale, not data being lost.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VaultKeyLookupService {

    private final VaultKeyRepository keyRepository;
    private final AddressbookEntryRepository entryRepository;

    /**
     * Resolve slot key ids across both stores, keyed by id. Ids that match nothing are simply absent —
     * callers already treat an unresolvable slot key as invalid, and after the FK removal a slot may
     * legitimately outlive the directory row it names.
     */
    public Map<String, KeyRef> findAllById(Collection<String> keyIds) {
        Map<String, KeyRef> byId = new LinkedHashMap<>();
        keyRepository.findAllById(keyIds).forEach(key ->
                byId.put(key.getId(), KeyRef.ofOrgKey(key.getId(), key.getOrganisationId(),
                        key.getAccountId(), key.getPublicKey(), key.getAccountName(), key.getLabel(),
                        key.getAssurance())));
        entryRepository.findAllById(keyIds).forEach(entry ->
                byId.put(entry.getId(), KeyRef.ofAddressbookEntry(entry.getId(), entry.getOrganisationId(),
                        entry.getPublicKey(), entry.getDisplayName(), entry.getDescription(),
                        entry.getAssurance())));
        return byId;
    }
}
