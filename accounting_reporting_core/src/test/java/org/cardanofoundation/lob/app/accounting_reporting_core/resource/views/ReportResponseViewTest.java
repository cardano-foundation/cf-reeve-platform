package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import static org.junit.jupiter.api.Assertions.*;
import static org.zalando.problem.Status.NOT_FOUND;

import java.util.List;

import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.junit.jupiter.api.Test;

class ReportResponseViewTest {

    @Test
    void reportResponseViewCreateFail(){
        ThrowableProblem issue = Problem.builder()
                .withTitle("ORGANISATION_NOT_FOUND")
                .withDetail("Unable to find Organisation by Id: Testes")
                .withStatus(NOT_FOUND)
                .build();

        ReportResponseView responseView = ReportResponseView.createFail(issue);
        assertEquals("ORGANISATION_NOT_FOUND",responseView.getError().get().getTitle());
    }


    @Test
    void reportResponseViewCreateSuccess(){
        ReportView repo = new ReportView();

        ReportResponseView responseView = ReportResponseView.createSuccess(List.of(repo));
        assertEquals(1,responseView.getTotal());
    }
}
