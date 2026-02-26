package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

@Getter
@Setter
@AllArgsConstructor
public class ExtractionTransactionView {
    private boolean success;

    private long total;

    private List<ExtractionTransactionItemView> transactions;

    private Optional<ProblemDetail> error;

    private int page;
    private int size;

    public static ExtractionTransactionView createSuccess(List<ExtractionTransactionItemView> transactions, long totalElements, int page, int limit) {
        return new ExtractionTransactionView(
                true,
                totalElements,
                transactions,
                Optional.empty(),
                page,
                limit
        );
    }

    public static ExtractionTransactionView createFail(ProblemDetail error)  {
        return new ExtractionTransactionView(false, 0L, List.of(), Optional.of(error), 0, 0);
    }
}
