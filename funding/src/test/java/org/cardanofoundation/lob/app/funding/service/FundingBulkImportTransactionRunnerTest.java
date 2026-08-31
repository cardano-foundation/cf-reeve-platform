package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

    @Test
    void runGroupAndRollBackOnFailure_invokesSupplierExactlyOnceAndReturnsItsResult_whenNotFailed() {
        List<String> expected = List.of("ok");
        AtomicInteger invocations = new AtomicInteger();

        List<String> result = runner.runGroupAndRollBackOnFailure(() -> {
            invocations.incrementAndGet();
            return expected;
        }, r -> false);

        assertThat(result).isSameAs(expected);
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void runGroupAndRollBackOnFailure_invokesSupplierExactlyOnceAndReturnsItsResult_whenFailed() {
        // Outside a Spring container there is no active transaction to mark rollback-only — this test
        // only proves the work still runs exactly once and its result is still returned regardless of
        // what the predicate says; the actual DB rollback behavior is integration-test territory (see
        // FundingBulkImportE2ETest).
        List<String> expected = List.of("row error");
        AtomicInteger invocations = new AtomicInteger();

        List<String> result = runner.runGroupAndRollBackOnFailure(() -> {
            invocations.incrementAndGet();
            return expected;
        }, r -> true);

        assertThat(result).isSameAs(expected);
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void runGroupAndRollBackOnFailure_evaluatesThePredicateAgainstTheSuppliersOwnResult() {
        AtomicInteger predicateSawSize = new AtomicInteger(-1);

        runner.runGroupAndRollBackOnFailure(() -> List.of("a", "b", "c"), result -> {
            predicateSawSize.set(result.size());
            return false;
        });

        assertThat(predicateSawSize.get()).isEqualTo(3);
    }

}
