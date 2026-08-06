package org.cardanofoundation.lob.app.funding.service;

import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs one Projects-CSV group (a root project row plus its sub-project rows) inside a single
 * transaction — a separate bean (not a method on {@link FundingBulkImportService}) so the
 * {@code @Transactional} boundary is entered through Spring's proxy rather than via
 * self-invocation, which would silently skip the annotation. Root creation and every sub-project
 * upsert in the group join this one transaction (default REQUIRES propagation), so when the group
 * ends up with a newly-created root but no surviving sub-project — every attempted sub-project row
 * failed — the transaction is marked rollback-only rather than leaving an orphan root behind.
 * Other groups in the same file are unaffected: each gets its own call, and therefore its own
 * transaction.
 */
@Service
public class FundingProjectGroupTransactionRunner {

    @Transactional
    public FundingBulkImportService.ProjectGroupOutcome runInTransaction(
            Supplier<FundingBulkImportService.ProjectGroupOutcome> work) {
        FundingBulkImportService.ProjectGroupOutcome outcome = work.get();
        if (outcome.orphanRoot() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return outcome;
    }

}
