package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;

class FundingBulkImportTransactionRunnerTest {

    private final FundingBulkImportTransactionRunner runner = new FundingBulkImportTransactionRunner();

    @Test
    void runAndRollBack_invokesSupplierExactlyOnceAndReturnsItsResult() {
        FundingBulkImportResult expected = FundingBulkImportResult.builder().projectsCreated(3).build();
        AtomicInteger invocations = new AtomicInteger();

        FundingBulkImportResult result = runner.runAndRollBack(() -> {
            invocations.incrementAndGet();
            return expected;
        });

        assertThat(result).isSameAs(expected);
        assertThat(invocations.get()).isEqualTo(1);
    }

}
