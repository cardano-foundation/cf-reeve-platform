package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;


import java.util.List;
import java.util.Optional;

import lombok.*;

import org.zalando.problem.Problem;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ReportResponseView {

    private boolean success;
    private Long total;
    private ReportResponseStatisticView reportStatistics;
    private List<ReportView> report;
    private Optional<Problem> error;

    public static ReportResponseView createSuccess(List<ReportView> reportView) {
        return new ReportResponseView(
                true,
                reportView.stream().count(),
                ReportResponseStatisticView.builder().total(reportView.stream().count()).build(),
                reportView,
                Optional.empty()
        );
    }

    public static ReportResponseView createSuccess(List<ReportView> reportView, ReportResponseStatisticView statisticView) {
        return new ReportResponseView(
                true,
                reportView.stream().count(),
                statisticView,
                reportView,
                Optional.empty()
        );
    }

    public static ReportResponseView createFail(Problem error) {
        return new ReportResponseView(false, 0L, ReportResponseStatisticView.builder().build(), List.of(), Optional.of(error));
    }
}
