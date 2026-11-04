package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

public record DocumentUploadedView(String documentId, String contentHash, LocalDateTime createdAt) {
}
