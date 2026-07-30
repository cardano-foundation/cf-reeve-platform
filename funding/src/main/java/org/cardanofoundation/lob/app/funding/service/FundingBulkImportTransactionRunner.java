package org.cardanofoundation.lob.app.funding.service;

import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;

/**
 * Runs a bulk-import dry-run inside one transaction and always rolls it back — this is a separate
 * bean (not a method on {@link FundingBulkImportService}) so the {@code @Transactional} boundary is
 * entered through Spring's proxy rather than via self-invocation, which would silently skip the
 * annotation. Every project/milestone/event created while {@code work} runs shares this one
 * transaction (they join it via the default REQUIRES propagation), so marking it rollback-only
 * discards the whole dry run atomically.
 */
@Service
public class FundingBulkImportTransactionRunner {

    @Transactional
    public FundingBulkImportResult runAndRollBack(Supplier<FundingBulkImportResult> work) {
        FundingBulkImportResult result = work.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return result;
    }

}
