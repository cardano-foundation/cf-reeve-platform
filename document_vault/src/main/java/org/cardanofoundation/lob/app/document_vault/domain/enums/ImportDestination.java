package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** Which store an imported key card was written to. */
public enum ImportDestination {
    /** The card's subject was the caller, so it is their own key: it binds to their Keycloak account
     *  and appears in {@code /keys/me}. */
    ORG_KEY,
    /** The card was about somebody else, so it is a contact: an addressbook entry with no account. */
    ADDRESSBOOK_ENTRY
}
