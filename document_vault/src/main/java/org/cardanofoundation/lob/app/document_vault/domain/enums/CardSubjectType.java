package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** Who a key card is about. */
public enum CardSubjectType {
    /** The holder logs into Reeve; subjectId is their Keycloak `sub`. */
    REEVE_ACCOUNT,
    /** The holder has no Reeve login (e.g. an external auditor); subjectId is an Indexer-minted UUID.
     *  They are addressable as a recipient and read published documents in the Indexer. */
    EXTERNAL
}
