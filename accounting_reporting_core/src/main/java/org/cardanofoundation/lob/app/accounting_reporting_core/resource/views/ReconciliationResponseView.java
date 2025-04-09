package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;

@Getter
@Setter
@AllArgsConstructor
public class ReconciliationResponseView {
    private Long total = 0L;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Optional<LocalDate> lastDateFrom;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Optional<LocalDate> lastDateTo;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Optional<LocalDate> lastReconciledDate;
    private TransactionReconciliationStatisticView statistic;

    @JsonProperty("transactions")
    private Set<TransactionReconciliationTransactionsView> transactions = new HashSet<>();
}
