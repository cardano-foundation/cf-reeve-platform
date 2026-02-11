package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(value = {"lob.accounting_reporting_core.enabled", "lob.accounting_reporting_core.jobs.unstuck.enabled"}, havingValue = "true", matchIfMissing = true)
public class TxUnstuckJob {

    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    @Value("${lob.accounting_reporting_core.jobs.unstuck.stuck_delay:PT10M}")
    private String stuckDelay;

    @Scheduled(
            fixedDelayString = "${lob.accounting_reporting_core.jobs.unstuck.delay:PT30M}",
            initialDelayString = "${lob.accounting_reporting_core.jobs.unstuck.initial_delay:PT1M}")
    @Transactional
    public void execute() {
        LocalDateTime now = LocalDateTime.now(clock);
        Duration delay = Duration.parse(stuckDelay);
        LocalDateTime plus = now.plus(delay);
        List<TransactionEntity> stuckTransactions = accountingCoreTransactionRepository.findStuckTransactions(plus);
        if(stuckTransactions.isEmpty()) {
            log.debug("No stuck transactions found");
            return;
        }
        log.info("Found {} stuck transactions, requesting an status update from the publisher.", stuckTransactions.size());
        Map<String, List<String>> organisationTransactionIdMap = stuckTransactions.stream()
                .collect(Collectors.groupingBy(
                        o -> o.getOrganisation().getId(),
                        Collectors.mapping(TransactionEntity::getId, Collectors.toList())
                ));
        applicationEventPublisher.publishEvent(new TransactionStatusRequestEvent(organisationTransactionIdMap));
    }
}
