package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.LedgerService;

@Service
@Slf4j
@RequiredArgsConstructor
public class DispatcherJob {

    private final LedgerService ledgerService;

    @Value("${ledger.dispatch.pull.limit:1000}")
    private int dispatchPendingPullLimit = 1_000;

    @Scheduled(
            fixedDelayString = "${lob.blockchain.dispatcher.fixed_delay:PT1M}",
            initialDelayString = "${lob.blockchain.dispatcher.initial_delay:PT10S}")
    public void execute() {
        log.debug("Executing TransactionDispatcherJob...");

        ledgerService.dispatchPending(dispatchPendingPullLimit);

        log.debug("Finished executing TransactionDispatcherJob.");
    }

}
