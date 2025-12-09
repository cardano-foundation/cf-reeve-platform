package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.event.TransactionRolledBackEvent;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionRolledBackEventHandler {
    @Value("${lob.blockchain-publisher.rollback.enabled}")
    private Optional<Boolean> rollbackEnabled;

    private final TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;

    @Async
    @EventListener
    @Transactional()
    public void handleTransactionRolledBack(TransactionRolledBackEvent event) {
        String transactionId = event.getTransactionId();
        if (!rollbackEnabled.orElse(false)) {
            log.info("Rollback feature is disabled, skipping TransactionRolledBackEvent for transaction: {}", transactionId);
            return;
        }
        log.info("Handling TransactionRolledBackEvent for transaction: {}", transactionId);
        try {
            transactionEntityRepositoryGateway.removePublishedTransactions(transactionId);
        } catch (Exception e) {
            log.error("\n\n#####\nFailed to remove published transaction: " + transactionId, e);
        }
    }
}
