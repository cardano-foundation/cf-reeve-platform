package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;

public record ReconWithViolationDto(
        ReconcilationEntity recon,
        Violation violation) {
}
