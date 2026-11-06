package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

/** Listing metadata only — ciphertext/slots are served exclusively by the envelope-fetch endpoint. */
public record DocumentView(String documentId,
                           String fileName,
                           String contentType,
                           String description,
                           long sizeBytes,
                           String contentHash,
                           int envelopeVersion,
                           VaultDocumentStatus status,
                           LedgerDispatchStatus ledgerDispatchStatus,
                           String ledgerDispatchError,
                           String txHash,
                           String ipfsCid,
                           String createdByName,
                           LocalDateTime createdAt) {
}
