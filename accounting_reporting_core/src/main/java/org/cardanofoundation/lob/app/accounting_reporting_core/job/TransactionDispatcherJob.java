package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.LedgerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service("accounting_core.TransactionDispatcherJob")
@Slf4j
@RequiredArgsConstructor
public class TransactionDispatcherJob {

    private final LedgerService ledgerService;

    @Value("${ledger.dispatch.pull.limit:1000}")
    private int dispatchPendingPullLimit = 1_000;

    @Scheduled(
            fixedDelayString = "${lob.blockchain.dispatcher.fixedDelay:PT1M}",
            initialDelayString = "${lob.blockchain.dispatcher.initialDelay:PT10S}")
    public void execute() {
        log.info("Executing TransactionDispatcherJob...");

        ledgerService.dispatchPending(dispatchPendingPullLimit);

        log.info("Finished executing TransactionDispatcherJob.");
    }

}
