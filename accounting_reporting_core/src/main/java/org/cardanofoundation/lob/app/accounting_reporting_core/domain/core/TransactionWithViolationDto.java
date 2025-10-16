package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationViolation;

public record TransactionWithViolationDto(
        TransactionEntity tx,
        ReconcilationViolation violation) {
}
