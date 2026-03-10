package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.time.LocalDate;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.IntervalType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ReconciliationStatisticRequest extends BaseRequest {

    @Schema(example = "2024-01-01")
    @NotNull(message = "dateFrom is mandatory and must not be null.")
    private LocalDate dateFrom;

    @Schema(example = "2024-12-31")
    @NotNull(message = "dateTo is mandatory and must not be null.")
    private LocalDate dateTo;

    @Schema(example = "MONTH", nullable = true)
    @Nullable
    private IntervalType aggregate;

}
