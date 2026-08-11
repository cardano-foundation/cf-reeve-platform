package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** What kind of thing a recipient is — which store it came from, and so what it can do. */
public enum RecipientKind {
    /** A Keycloak user in this organisation who owns their private key. They can fetch and decrypt in
     *  Reeve. Addressed by account id. */
    ORG_KEY,
    /** An addressbook contact: a public key, no Reeve account. They read published documents in the
     *  Indexer rather than signing in. Addressed by entry id. */
    ADDRESSBOOK_ENTRY
}
