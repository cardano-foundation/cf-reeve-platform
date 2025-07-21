package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.time.LocalDate;
import java.util.Set;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BatchSearchRequest extends BaseRequest {

    @ArraySchema(arraySchema = @Schema(example = "[\"APPROVE\", \"PENDING\", \"INVALID\", \"PUBLISH\", \"PUBLISHED\"]", implementation = LedgerDispatchStatusView.class))
    private Set<LedgerDispatchStatusView> batchStatistics = Set.of();

    @ArraySchema(arraySchema = @Schema(example = "[\"OK\",\"NOK\"]", implementation = TransactionStatus.class))
    private Set<TransactionStatus> txStatus = Set.of();

    @ArraySchema(arraySchema = @Schema(example = "[\"VendorPayment\",\"BillCredit\"]", implementation = TransactionType.class))
    private Set<TransactionType> transactionTypes = Set.of();

    @Schema(example = "2014-01-01")
    @Nullable
    private LocalDate from;

    @Schema(example = "2024-12-31")
    @Nullable
    private LocalDate To;

    @Schema(example = "user name")
    @Nullable
    private String createdBy;

    @Schema(example = "763d9944314012fffdf3d19aa924f750576f467aaf2bbd217f74dd549308597a")
    @Nullable
    private String batchId;

    @JsonIgnore
    private Integer limit;

    @JsonIgnore
    private Integer page;

}
