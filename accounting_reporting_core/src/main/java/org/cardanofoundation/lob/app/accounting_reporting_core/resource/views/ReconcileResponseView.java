package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.time.LocalDate;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonFormat;

@Getter
@Setter
@AllArgsConstructor
public class ReconcileResponseView {

    private String message;
    private String event = "RECONCILIATION";
    private Boolean success;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate dateFrom;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate dateTo;
    private Optional<ProblemDetail> error;

    public static ReconcileResponseView createSuccess(String message, LocalDate dateFrom, LocalDate dateTo) {
        return new ReconcileResponseView(
                message,
                "RECONCILIATION",
                true,
                dateFrom,
                dateTo,
                Optional.empty()
        );
    }

    public static ReconcileResponseView createFail(String message,
                                                   LocalDate dateFrom,
                                                   LocalDate dateTo,
                                                   ProblemDetail error) {
        return new ReconcileResponseView(
                message,
                "RECONCILIATION",
                false,
                dateFrom,
                dateTo,
                Optional.of(error));
    }

}
