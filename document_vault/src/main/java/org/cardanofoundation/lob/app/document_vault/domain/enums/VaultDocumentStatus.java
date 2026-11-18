package org.cardanofoundation.lob.app.document_vault.domain.enums;

/** DRAFT documents are mutable-in-lifecycle (deletable, purgeable); PUBLISHED locks forever. */
public enum VaultDocumentStatus {
    DRAFT,
    PUBLISHED
}
