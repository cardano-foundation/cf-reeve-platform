package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.time.LocalDate;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OrganisationTransactions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFinalisationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.BusinessRulesPipelineProcessor;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;

@Service
@Slf4j
@RequiredArgsConstructor
public class ERPIncomingDataProcessor {

    private final TransactionReconcilationService transactionReconcilationService;
    @Qualifier("selectorBusinessRulesProcessors")
    private final BusinessRulesPipelineProcessor businessRulesPipelineProcessor;
    private final TransactionBatchService transactionBatchService;
    private final DbSynchronisationUseCaseService dbSynchronisationUseCaseService;

    @Transactional
    public void initiateIngestion(String batchId,
                                  String organisationId,
                                  UserExtractionParameters userExtractionParameters,
                                  SystemExtractionParameters systemExtractionParameters) {
        log.info("Processing ERPIngestionStored event.");

        transactionBatchService.createTransactionBatch(
                batchId,
                organisationId,
                userExtractionParameters,
                systemExtractionParameters
        );

        log.info("Finished processing ERPIngestionStored event, event.");
    }

    @Transactional
    public void continueIngestion(String organisationId,
                                  String batchId,
                                  int totalTransactionsCount,
                                  Set<TransactionEntity> transactions,
                                  ProcessorFlags processorFlags) {
        log.info("Processing ERPTransactionChunk event, batchId: {}, transactions: {}", batchId, transactions.size());

        val allOrgTransactions = new OrganisationTransactions(organisationId, transactions);

        // run or re-run business rules
        businessRulesPipelineProcessor.run(allOrgTransactions,processorFlags);

        dbSynchronisationUseCaseService.execute(batchId,
                allOrgTransactions,
                totalTransactionsCount,
                processorFlags
        );

        log.info("PASSING transactions: {}", transactions.size());
    }

    @Transactional
    public void initiateReconcilation(ReconcilationStartedEvent reconcilationStartedEvent) {
        log.info("Processing ReconcilationStartedEvent, event: {}", reconcilationStartedEvent);

        transactionReconcilationService.createReconcilation(
                reconcilationStartedEvent.getReconciliationId(),
                reconcilationStartedEvent.getOrganisationId(),
                reconcilationStartedEvent.getFrom(),
                reconcilationStartedEvent.getTo()
        );

        log.info("Finished processing ReconcilationStartedEvent, event: {}", reconcilationStartedEvent);
    }

    @Transactional
    public void continueReconcilation(String reconcilationId,
                                      String organisationId,
                                      LocalDate fromDate,
                                      LocalDate toDate,
                                      Set<TransactionEntity> chunkDetachedTxEntities) {
        log.info("Processing ReconcilationChunkEvent, event, reconcilationId: {}", reconcilationId);

        val organisationTransactions = new OrganisationTransactions(organisationId, chunkDetachedTxEntities);

        // run or re-run business rules
        businessRulesPipelineProcessor.run(organisationTransactions,new ProcessorFlags(ProcessorFlags.Trigger.RECONCILATION));

        transactionReconcilationService.reconcileChunk(
                reconcilationId,
                organisationId,
                fromDate,
                toDate,
                organisationTransactions.transactions()
        );

        log.info("Finished processing ReconcilationChunkEvent, event: {}", reconcilationId);
    }

    @Transactional
    public void finialiseReconcilation(ReconcilationFinalisationEvent event) {
        log.info("Processing finialiseReconcilation, event: {}", event);

        val reconcilationId = event.getReconciliationId();
        val organisationId = event.getOrganisationId();

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId);

        log.info("Finished processing ReconcilationChunkEvent, event: {}", event);
    }

}
