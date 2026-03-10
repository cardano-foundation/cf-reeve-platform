package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.time.LocalDateTime;

import org.springframework.lang.Nullable;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationViolation;

public record TransactionWithViolationDto(
        @Nullable TransactionEntity tx,
        ReconcilationViolation violation,
        LocalDateTime lastReconciledDate) {
}
