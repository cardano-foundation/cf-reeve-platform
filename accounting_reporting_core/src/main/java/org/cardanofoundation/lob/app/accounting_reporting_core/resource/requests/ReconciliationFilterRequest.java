package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
//@Builder todo: For testing
@NoArgsConstructor
@Slf4j
public class ReconciliationFilterRequest extends BaseRequest {

    @Schema(example = "UNRECONCILED")
    @NotNull(message = "Filter is mandatory and must not be blank or null. Options are: RECONCILED, UNRECONCILED, UNPROCESSED")
    private ReconciliationFilterStatusRequest filter;

    @Schema(example = "2014-01-01")
    private Optional<LocalDate> dateFrom  = Optional.empty();

    @ArraySchema(arraySchema = @Schema(example = "[\"MISSING_IN_ERP\",\"IN_PROCESSING\",\"NEW_IN_ERP\",\"NEW_VERSION_NOT_PUBLISHED\",\"NEW_VERSION\"]"))
    private Set<ReconciliationRejectionCodeRequest> reconciliationRejectionCode = new HashSet<>();

    @JsonIgnore
    private Integer limit;

    @JsonIgnore
    private Integer page;

}
