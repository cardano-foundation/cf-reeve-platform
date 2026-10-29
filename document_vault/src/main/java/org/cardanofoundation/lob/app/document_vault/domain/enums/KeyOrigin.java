package org.cardanofoundation.lob.app.document_vault.domain.enums;

/**
 * How a {@code VaultKeyEntity} entry came to exist. See
 * V1.7_100_14__lob_service_app_document_vault_module.sql for the authoritative column comment.
 */
public enum KeyOrigin {
    /** Enrolled directly by the owner via a passkey. */
    SELF_ENROLLED,
    /** Minted by an Indexer operator and handed over on an imported key card. */
    INDEXER_ISSUED
}
