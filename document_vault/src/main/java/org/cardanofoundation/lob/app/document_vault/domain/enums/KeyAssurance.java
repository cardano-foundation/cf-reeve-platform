package org.cardanofoundation.lob.app.document_vault.domain.enums;

/**
 * Custody tier of a {@code VaultKeyEntity} (blueprint I2, amended). This is PROVENANCE, not
 * storage: the value never upgrades after registration. See
 * V1.7_100_14__lob_service_app_document_vault_module.sql for the authoritative column comment.
 */
public enum KeyAssurance {
    /** The private half never left the owner's device. */
    PASSKEY,
    /** Indexer-minted and handed over on a card — an operator has seen it. */
    PORTABLE
}
