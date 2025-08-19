package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;


import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import org.zalando.problem.Problem;


@Getter
@Setter
@AllArgsConstructor
public class ReportResponseView {

    private boolean success;

    private Long total;
    private List<ReportView> report;
    private Optional<Problem> error;

    public static ReportResponseView createSuccess(List<ReportView> reportView) {
        return new ReportResponseView(
                true,
                reportView.stream().count(),
                reportView,
                Optional.empty()
        );
    }

    public static ReportResponseView createSuccess(List<ReportView> reportView, Long total) {
        return new ReportResponseView(
                true,
                total,
                reportView,
                Optional.empty()
        );
    }

    public static ReportResponseView createFail(Problem error) {
        return new ReportResponseView(false, 0L, List.of(), Optional.of(error));
    }
}
