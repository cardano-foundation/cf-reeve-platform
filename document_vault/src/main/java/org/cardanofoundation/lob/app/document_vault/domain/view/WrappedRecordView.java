package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.time.LocalDateTime;

public record WrappedRecordView(String credentialId, String record, int version, LocalDateTime updatedAt) {
}
