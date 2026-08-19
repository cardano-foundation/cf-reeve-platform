package org.cardanofoundation.lob.app.funding.service;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;

/**
 * Runs bulk-import work inside a transaction — this is a separate bean (not a method on
 * {@link FundingBulkImportService}) so the {@code @Transactional} boundary is entered through
 * Spring's proxy rather than via self-invocation, which would silently skip the annotation.
 */
@Service
public class FundingBulkImportTransactionRunner {

    /**
     * Runs a bulk-import dry-run inside one transaction and always rolls it back. Every
     * project/milestone/event created while {@code work} runs shares this one transaction (they join
     * it via the default REQUIRES propagation), so marking it rollback-only discards the whole dry
     * run atomically.
     */
    @Transactional
    public FundingBulkImportResult runAndRollBack(Supplier<FundingBulkImportResult> work) {
        FundingBulkImportResult result = work.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return result;
    }

    /**
     * Runs one Projects+Milestones group (a root project and everything upserted under it — its
     * sub-projects and milestones) inside its own transaction, rolling it back when {@code hasFailed}
     * says the group produced a row error. Without this, a validation failure partway through a group
     * (e.g. a sub-project total exceeding its parent's, which can only be known once every sibling
     * sub-project has been considered) leaves the rows that already succeeded — the root project, an
     * earlier sub-project or milestone — permanently persisted, even though the group as a whole is
     * being reported as failed.
     *
     * <p>The caller only invokes this when no transaction is already active — i.e. for a real (non
     * dry-run) import. During a dry run the whole request already runs inside {@link #runAndRollBack}'s
     * one transaction, which is unconditionally rolled back at the end regardless of any individual
     * group's outcome, so wrapping each group again there would only join that same transaction and
     * risks marking it rollback-only before an unrelated, later group's own commit attempt — which
     * would surface as {@code UnexpectedRollbackException} instead of a clean result.
     */
    @Transactional
    public <T> T runGroupAndRollBackOnFailure(Supplier<T> work, Predicate<T> hasFailed) {
        T result = work.get();
        if (hasFailed.test(result) && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return result;
    }

}
