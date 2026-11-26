package org.cardanofoundation.lob.app.document_vault.domain.view;

import org.cardanofoundation.lob.app.document_vault.domain.enums.ImportDestination;

/**
 * Where an imported card ended up, and what it became.
 *
 * The destination is stated rather than implied. {@code /addressbook/import} takes a card about you to
 * your organisation keys instead of the addressbook — the one case where the path does not describe the
 * outcome — and a caller should not have to know the routing rule to find their own key afterwards.
 *
 * Exactly one of {@code key} / {@code entry} is non-null, matching the destination.
 *
 * @param destination ORG_KEY when the card's subject is the caller; ADDRESSBOOK_ENTRY otherwise.
 * @param key         the organisation key, when destination is ORG_KEY. Appears in {@code /keys/me}.
 * @param entry       the addressbook contact, when destination is ADDRESSBOOK_ENTRY.
 */
public record ImportCardResultView(ImportDestination destination,
                                   VaultKeyView key,
                                   AddressbookEntryView entry) {

    public static ImportCardResultView ofOrgKey(VaultKeyView key) {
        return new ImportCardResultView(ImportDestination.ORG_KEY, key, null);
    }

    public static ImportCardResultView ofAddressbookEntry(AddressbookEntryView entry) {
        return new ImportCardResultView(ImportDestination.ADDRESSBOOK_ENTRY, null, entry);
    }
}
