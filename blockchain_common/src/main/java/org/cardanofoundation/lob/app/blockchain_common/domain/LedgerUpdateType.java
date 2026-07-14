package org.cardanofoundation.lob.app.blockchain_common.domain;

/**
 * Discriminator for {@link LedgerUpdatedEvent} so each consuming module only processes the updates relevant to it.
 */
public enum LedgerUpdateType {

    TRANSACTION,

    REPORT,

    SPENDING_EVENT,

    DOCUMENT

}
