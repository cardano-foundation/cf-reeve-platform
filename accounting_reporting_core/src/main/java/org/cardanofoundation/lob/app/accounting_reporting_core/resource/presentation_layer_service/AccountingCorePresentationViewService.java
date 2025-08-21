package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static java.math.BigDecimal.ZERO;
import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.FAILED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.LedgerDispatchStatusView.*;
import static org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.FailureResponses.transactionNotFoundResponse;
import static org.cardanofoundation.lob.app.accounting_reporting_core.utils.SortFieldMappings.TRANSACTION_ENTITY_FIELD_MAPPINGS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FilterOptions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.repository.ProjectRepository;
import org.cardanofoundation.lob.app.organisation.util.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Service
@org.jmolecules.ddd.annotation.Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
// presentation layer service
public class AccountingCorePresentationViewService {

    private final TransactionRepositoryGateway transactionRepositoryGateway;
    private final AccountingCoreService accountingCoreService;
    private final TransactionBatchRepositoryGateway transactionBatchRepositoryGateway;
    private final TransactionReconcilationRepository transactionReconcilationRepository;
    private final CostCenterRepository costCenterRepository;
    private final ProjectRepository projectRepository;
    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final JpaSortFieldValidator jpaSortFieldValidator;
    private final TransactionItemRepository transactionItemRepository;

    /**
     * TODO: waiting for refactoring the layer to remove this
     */
    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;

    public ReconciliationResponseView allReconciliationTransaction(ReconciliationFilterRequest body) {
        Object transactionsStatistic = accountingCoreTransactionRepository.findCalcReconciliationStatistic();
        Optional<ReconcilationEntity> latestReconcilation = transactionReconcilationRepository.findTopByOrderByCreatedAtDesc();
        Set<TransactionReconciliationTransactionsView> transactions;
        long count;
        if (body.getFilter().equals(ReconciliationFilterStatusRequest.UNRECONCILED)) {
            Set<Object> txDuplicated = new HashSet<>();
            transactions = accountingCoreTransactionRepository.findAllReconciliationSpecial(body.getReconciliationRejectionCode(), body.getDateFrom(), body.getDateTo(), body.getLimit(), body.getPage()).stream()
                    .filter(o -> {
                        if (o[0] instanceof TransactionEntity transactionEntity && !txDuplicated.contains((transactionEntity).getId())) {
                            txDuplicated.add((transactionEntity).getId());
                            return true;
                        }

                        if (o[1] instanceof ReconcilationViolation reconcilationViolation && !txDuplicated.contains((reconcilationViolation).getTransactionId())) {
                            txDuplicated.add((reconcilationViolation).getTransactionId());
                            return true;
                        }

                        return false;
                    })
                    .map(this::getReconciliationTransactionsSelector)
                    .sorted(Comparator.comparing(TransactionReconciliationTransactionsView::getId))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            count = accountingCoreTransactionRepository.findAllReconciliationSpecialCount(body.getReconciliationRejectionCode(), body.getDateFrom(), body.getDateTo(), body.getLimit(), body.getPage()).size();
        } else {
            transactions = accountingCoreTransactionRepository.findAllReconciliation(body.getFilter(), body.getSource(), body.getLimit(), body.getPage()).stream()
                    .map(this::getTransactionReconciliationView)
                    .collect(toSet());
            count = accountingCoreTransactionRepository.findAllReconciliationCount(body.getFilter(), body.getSource(), body.getLimit(), body.getPage()).size();
        }
        return new ReconciliationResponseView(
                count,
                latestReconcilation.flatMap(ReconcilationEntity::getFrom),
                latestReconcilation.flatMap(ReconcilationEntity::getTo),
                latestReconcilation.map(reconcilationEntity -> reconcilationEntity.getUpdatedAt().toLocalDate()),
                getTransactionReconciliationStatistic(transactionsStatistic),
                transactions
        );
    }

    public List<TransactionView> allTransactions(SearchRequest body) {
        List<TransactionEntity> transactions = transactionRepositoryGateway.findAllByStatus(
                body.getOrganisationId(),
                body.getStatus(),
                body.getTransactionType(),
                PageRequest.of(body.getPage(), body.getSize()));

        return transactions.stream()
                .map(this::getTransactionView)
                .sorted(Comparator.comparing(TransactionView::getAmountTotalLcy).reversed())
                .toList();
    }

    public Optional<TransactionView> transactionDetailSpecific(String transactionId) {
        Optional<TransactionEntity> transactionEntity = transactionRepositoryGateway.findById(transactionId);

        return transactionEntity.map(this::getTransactionView);
    }

    public Either<Problem, Optional<BatchView>> batchDetail(String batchId, List<TransactionProcessingStatus> txStatus, Pageable page, BatchFilterRequest batchFilterRequest) {
        if (page.getSort().isSorted()) {
            Optional<Sort.Order> notSortableProperty = page.getSort().get().filter(order -> {
                String property = Optional.ofNullable(TRANSACTION_ENTITY_FIELD_MAPPINGS.get(order.getProperty())).orElse(order.getProperty());

                return !jpaSortFieldValidator.isSortable(TransactionEntity.class, property);

            }).findFirst();
            if (notSortableProperty.isPresent()) {
                return Either.left(Problem.builder()
                        .withTitle("Invalid Sort Property")
                        .withDetail("Invalid sort: " + notSortableProperty.get().getProperty())
                        .build());
            }
            page = PageRequest.of(page.getPageNumber(), page.getPageSize(),
                    Sort.by(page.getSort().get().map(order -> new Sort.Order(order.getDirection(),
                    Optional.ofNullable(TRANSACTION_ENTITY_FIELD_MAPPINGS.get(order.getProperty())).orElse(order.getProperty()))).toList()));
        }
        Pageable finalPage = page;
        return Either.right(transactionBatchRepositoryGateway.findById(batchId).map(transactionBatchEntity -> {
                    Page<TransactionEntity> transactions = this.getTransaction(transactionBatchEntity, txStatus, finalPage, batchFilterRequest);

                    BatchStatisticsView statistic = BatchStatisticsView.from(batchId, transactionBatchEntity.getBatchStatistics().orElse(new BatchStatistics()));
                    FilteringParametersView filteringParameters = this.getFilteringParameters(transactionBatchEntity.getFilteringParameters());

                    return new BatchView(
                            transactionBatchEntity.getId(),
                            transactionBatchEntity.getCreatedAt().toString(),
                            transactionBatchEntity.getUpdatedAt().toString(),
                            transactionBatchEntity.getCreatedBy(),
                            transactionBatchEntity.getUpdatedBy(),
                            transactionBatchEntity.getOrganisationId(),
                            transactionBatchEntity.getStatus(),
                            statistic,
                            filteringParameters,
                            transactions.stream().map(this::getTransactionView).toList(),
                            transactionBatchEntity.getDetails().orElse(Details.builder().build()).getBag(),
                            transactions.getTotalElements()
                    );
                }
        ));
    }

    public Either<Problem, BatchsDetailView> listAllBatch(BatchSearchRequest body, Sort sort) {
        BatchsDetailView batchDetailView = new BatchsDetailView();
        Either<Problem, List<TransactionBatchEntity>> transactionBatchEntitiesE = transactionBatchRepositoryGateway.findByFilter(body, sort);
        if (transactionBatchEntitiesE.isLeft()) {
            return Either.left(transactionBatchEntitiesE.getLeft());
        }
        List<TransactionBatchEntity> transactionBatchEntities = transactionBatchEntitiesE.get();
        List<BatchView> batches = transactionBatchEntities
                .stream()
                .map(
                        transactionBatchEntity -> {
                            BatchStatisticsView statistic = BatchStatisticsView.from(transactionBatchEntity.getId(), transactionBatchEntity.getBatchStatistics().orElse(new BatchStatistics()));
                            return new BatchView(
                                    transactionBatchEntity.getId(),
                                    transactionBatchEntity.getCreatedAt().toString(),
                                    transactionBatchEntity.getUpdatedAt().toString(),
                                    transactionBatchEntity.getCreatedBy(),
                                    transactionBatchEntity.getUpdatedBy(),
                                    transactionBatchEntity.getOrganisationId(),
                                    transactionBatchEntity.getStatus(),
                                    statistic,
                                    this.getFilteringParameters(transactionBatchEntity.getFilteringParameters()),
                                    List.of(),
                                    transactionBatchEntity.getDetails().orElse(Details.builder().build()).getBag(),
                                    null // transactions are not loaded here
                            );
                        }
                ).toList();

        batchDetailView.setBatchs(batches);
        batchDetailView.setTotal(transactionBatchRepositoryGateway.findByFilterCount(body));

        return Either.right(batchDetailView);
    }

    @Transactional
    public Either<Problem, Void> extractionTrigger(ExtractionRequest body) {
        UserExtractionParameters fp = getUserExtractionParameters(body);

        return accountingCoreService.scheduleIngestion(fp, body.getExtractorType(), Optional.ofNullable(body.getFile()), body.getParameters());
    }

    private UserExtractionParameters getUserExtractionParameters(ExtractionRequest body) {
        ArrayList<String> transactionNumbers = new ArrayList<>(body.getTransactionNumbers());
        transactionNumbers.removeIf(String::isEmpty);

        return UserExtractionParameters.builder()
                .from(body.getDateFrom().isEmpty() ? LocalDate.EPOCH : LocalDate.parse(body.getDateFrom()))
                .to(body.getDateTo().isEmpty() ? LocalDate.now() : LocalDate.parse(body.getDateTo()))
                .organisationId(body.getOrganisationId())
                .transactionTypes(body.getTransactionType())
                .transactionNumbers(transactionNumbers)
                .build();
    }

    public Either<List<Problem>, Void> extractionValidation(ExtractionRequest body) {
        UserExtractionParameters userExtractionParameters = getUserExtractionParameters(body);
        return accountingCoreService.validateIngestion(userExtractionParameters, body.getExtractorType(), Optional.ofNullable(body.getFile()), body.getParameters());
    }

    @Transactional
    public List<TransactionProcessView> approveTransactions(TransactionsRequest transactionsRequest) {
        return transactionRepositoryGateway.approveTransactions(transactionsRequest)
                .stream()
                .map(txEntityE -> txEntityE.fold(
                        txProblem -> TransactionProcessView.createFail(txProblem.getId(), txProblem.getProblem()),
                        success -> TransactionProcessView.createSuccess(success.getId())
                ))
                .toList();
    }

    @Transactional
    public List<TransactionProcessView> approveTransactionsPublish(TransactionsRequest transactionsRequest) {
        return transactionRepositoryGateway.approveTransactionsDispatch(transactionsRequest)
                .stream()
                .map(txEntityE -> txEntityE.fold(
                        txProblem -> TransactionProcessView.createFail(txProblem.getId(), txProblem.getProblem()),
                        success -> TransactionProcessView.createSuccess(success.getId())
                ))
                .toList();
    }

    @Transactional
    public TransactionItemsProcessRejectView rejectTransactionItems(TransactionItemsRejectionRequest transactionItemsRejectionRequest) {
        Optional<TransactionEntity> txM = transactionRepositoryGateway.findById(transactionItemsRejectionRequest.getTransactionId());
        if (txM.isEmpty()) {
            Either<IdentifiableProblem, TransactionEntity> errorE = transactionNotFoundResponse(transactionItemsRejectionRequest.getTransactionId());
            return TransactionItemsProcessRejectView.createFail(transactionItemsRejectionRequest.getTransactionId(), errorE.getLeft().getProblem());

        }
        TransactionEntity tx = txM.orElseThrow();
        Set<TransactionItemsProcessView> items = transactionRepositoryGateway.rejectTransactionItems(tx, transactionItemsRejectionRequest.getTransactionItemsRejections())
                .stream()
                .map(txItemEntityE -> txItemEntityE.fold(txProblem -> TransactionItemsProcessView.createFail(txProblem.getId(), txProblem.getProblem())
                        , success -> TransactionItemsProcessView.createSuccess(success.getId())
                ))
                .collect(toSet());

        return TransactionItemsProcessRejectView.createSuccess(
                tx.getId(),
                this.getTransactionDispatchStatus(tx),
                items
        );
    }

    @Transactional
    public BatchReprocessView scheduleReIngestionForFailed(String batchId) {
        Either<Problem, Void> txM = accountingCoreService.scheduleReIngestionForFailed(batchId);

        if (txM.isEmpty()) {
            return BatchReprocessView.createFail(batchId, txM.getLeft());

        }

        return BatchReprocessView.createSuccess(batchId);
    }

    private FilteringParametersView getFilteringParameters(FilteringParameters filteringParameters) {
        return new FilteringParametersView(
                filteringParameters.getTransactionTypes(),
                filteringParameters.getFrom(),
                filteringParameters.getTo(),
                filteringParameters.getAccountingPeriodFrom(),
                filteringParameters.getAccountingPeriodTo(),
                filteringParameters.getTransactionNumbers()
        );
    }

    private Page<TransactionEntity> getTransaction(TransactionBatchEntity transactionBatchEntity, List<TransactionProcessingStatus> status, Pageable pageable, BatchFilterRequest batchFilterRequest) {
        return accountingCoreTransactionRepository.findAllByBatchId(transactionBatchEntity.getId(), status,
                        batchFilterRequest.getTransactionTypes(),
                        batchFilterRequest.getDocumentNumbers(),
                        batchFilterRequest.getCurrencyCustomerCodes(),
                        batchFilterRequest.getMinFCY(),
                        batchFilterRequest.getMaxFCY(),
                        batchFilterRequest.getMinLCY(),
                        batchFilterRequest.getMaxLCY(),
                        batchFilterRequest.getVatCustomerCodes(),
                        batchFilterRequest.getParentCostCenterCustomerCodes(),
                        batchFilterRequest.getCostCenterCustomerCodes(),
                        batchFilterRequest.getCounterPartyCustomerCodes(),
                        batchFilterRequest.getCounterPartyTypes(),
                        batchFilterRequest.getDebitAccountCodes(),
                        batchFilterRequest.getCreditAccountCodes(),
                        batchFilterRequest.getEventCodes(),
                        pageable);
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationView(TransactionEntity transactionEntity) {
        return new TransactionReconciliationTransactionsView(
                transactionEntity.getId(),
                transactionEntity.getInternalTransactionNumber(),
                transactionEntity.getEntryDate(),
                transactionEntity.getTransactionType(),
                DataSourceView.NETSUITE,
                Optional.of(transactionEntity.getOverallStatus()),
                Optional.of(getTransactionDispatchStatus(transactionEntity)),
                Optional.of(transactionEntity.getAutomatedValidationStatus()),
                transactionEntity.getTransactionApproved(),
                transactionEntity.getLedgerDispatchApproved(),
                transactionEntity.getTotalAmountLcy(),
                false,
                transactionEntity.getReconcilation().flatMap(reconcilation -> reconcilation.getSource().map(TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(reconcilation -> reconcilation.getSink().map(TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(reconcilation -> reconcilation.getFinalStatus().map(TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),

                transactionEntity.getLastReconcilation().map(reconcilationEntity -> reconcilationEntity.getViolations().stream()
                                .filter(reconcilationViolation -> reconcilationViolation.getTransactionId().equals(transactionEntity.getId()))
                                .map(reconcilationViolation -> ReconciliationRejectionCodeRequest.of(reconcilationViolation.getRejectionCode(), transactionEntity.getLedgerDispatchApproved()))
                                .collect(toSet()))
                        .orElse(new LinkedHashSet<>()),
                transactionEntity.getLastReconcilation().map(CommonEntity::getCreatedAt).orElse(null),
                getTransactionItemView(transactionEntity),
                getViolations(transactionEntity)

        );
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationViolationView(ReconcilationViolation reconcilationViolation) {
        return new TransactionReconciliationTransactionsView(
                reconcilationViolation.getTransactionId(),
                reconcilationViolation.getTransactionInternalNumber(),
                reconcilationViolation.getTransactionEntryDate(),
                reconcilationViolation.getTransactionType(),
                DataSourceView.NETSUITE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                reconcilationViolation.getAmountLcySum(),
                false,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                Set.of(ReconciliationRejectionCodeRequest.of(reconcilationViolation.getRejectionCode(), false)),
                null,
                new LinkedHashSet<>(),
                new LinkedHashSet<>()

        );
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationViolationView() {
        return new TransactionReconciliationTransactionsView(
                "",
                "",
                LocalDate.now(),
                TransactionType.CardCharge,
                DataSourceView.NETSUITE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                BigDecimal.valueOf(123),
                false,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                new LinkedHashSet<>(),
                null,
                null,
                null

        );
    }

    private TransactionView getTransactionView(TransactionEntity transactionEntity) {
        return new TransactionView(
                transactionEntity.getId(),
                transactionEntity.getInternalTransactionNumber(),
                transactionEntity.getEntryDate(),
                transactionEntity.getTransactionType(),
                DataSourceView.NETSUITE,
                transactionEntity.getOverallStatus(),
                getTransactionDispatchStatus(transactionEntity),
                transactionEntity.getAutomatedValidationStatus(),
                transactionEntity.getLedgerDispatchStatus(),
                transactionEntity.getTransactionApproved(),
                transactionEntity.getLedgerDispatchApproved(),
                transactionEntity.getTotalAmountLcy(),
                transactionEntity.hasAnyRejection(),
                transactionEntity.getReconcilation().flatMap(reconcilation -> reconcilation.getSource().map(TransactionView.ReconciliationCodeView::of))
                        .orElse(TransactionView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(reconcilation -> reconcilation.getSink().map(TransactionView.ReconciliationCodeView::of))
                        .orElse(TransactionView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(reconcilation -> reconcilation.getFinalStatus().map(TransactionView.ReconciliationCodeView::of))
                        .orElse(TransactionView.ReconciliationCodeView.NEVER),

                transactionEntity.getLastReconcilation().map(reconcilationEntity -> reconcilationEntity.getViolations().stream()
                                .filter(reconcilationViolation -> reconcilationViolation.getTransactionId().equals(transactionEntity.getId()))
                                .map(reconcilationViolation -> ReconciliationRejectionCodeRequest.of(reconcilationViolation.getRejectionCode(), transactionEntity.getLedgerDispatchApproved()))
                                .collect(toSet()))
                        .orElse(new LinkedHashSet<>()),
                transactionEntity.getLastReconcilation().map(CommonEntity::getCreatedAt).orElse(null),
                getTransactionItemView(transactionEntity),
                getViolations(transactionEntity)

        );
    }


    public LedgerDispatchStatusView getTransactionDispatchStatus(TransactionEntity transactionEntity) {
        if (FAILED == transactionEntity.getAutomatedValidationStatus()) {
            if (transactionEntity.getViolations().stream().anyMatch(v -> v.getSource() == ERP)) {
                return INVALID;
            }
            if (transactionEntity.hasAnyRejection()) {
                if (transactionEntity.getItems().stream().anyMatch(transactionItemEntity -> transactionItemEntity.getRejection().stream().anyMatch(rejection -> rejection.getRejectionReason().getSource() == ERP))) {
                    return INVALID;
                }
                return PENDING;
            }
            return PENDING;
        }

        if (transactionEntity.hasAnyRejection()) {
            if (transactionEntity.getItems().stream().anyMatch(transactionItemEntity -> transactionItemEntity.getRejection().stream().anyMatch(rejection -> rejection.getRejectionReason().getSource() == ERP))) {
                return INVALID;
            }
            return PENDING;
        }

        switch (transactionEntity.getLedgerDispatchStatus()) {
            case NOT_DISPATCHED, MARK_DISPATCH -> {
                if (Boolean.TRUE.equals(transactionEntity.getLedgerDispatchApproved())) {
                    return PUBLISHED;
                }

                if (Boolean.TRUE.equals(transactionEntity.getTransactionApproved())) {
                    return PUBLISH;
                }
            }

            case DISPATCHED, COMPLETED, FINALIZED -> {
                return PUBLISHED;
            }
        }

        return APPROVE;
    }

    private TransactionReconciliationStatisticView getTransactionReconciliationStatistic(Object transactionsStatistic) {

        Object[] result = (Object[]) transactionsStatistic;
        return new TransactionReconciliationStatisticView(
                (Integer) ((Long) result[0]).intValue(),
                (Integer) ((Long) result[1]).intValue(),
                (Integer) ((Long) result[2]).intValue(),
                (Integer) ((Long) result[3]).intValue(),
                (Integer) ((Long) result[4]).intValue(),
                (Long) result[5],
                (Integer) ((Long) result[6]).intValue(),
                (Long) result[7],
                (Integer) (((Long) result[5]).intValue() + ((Long) result[6]).intValue())
        );
    }

    private Set<TransactionItemView> getTransactionItemView(TransactionEntity transaction) {
        return transaction.getItems().stream().map(item -> {
            Optional<CostCenter> itemCostCenter = Optional.empty();
            Optional<Project> itemProject = Optional.empty();
            if (transaction.getOrganisation() != null) {
                itemCostCenter = costCenterRepository.findById(new CostCenter.Id(transaction.getOrganisation().getId(), item.getCostCenter().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode).orElse("")));
                itemProject = projectRepository.findById(new Project.Id(transaction.getOrganisation().getId(), item.getProject().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode).orElse("")));
            }
            return new TransactionItemView(
                    item.getId(),
                    item.getAccountDebit().map(Account::getCode).orElse(""),
                    item.getAccountDebit().flatMap(Account::getName).orElse(""),
                    item.getAccountDebit().flatMap(Account::getRefCode).orElse(""),
                    item.getAccountCredit().map(Account::getCode).orElse(""),
                    item.getAccountCredit().flatMap(Account::getName).orElse(""),
                    item.getAccountCredit().flatMap(Account::getRefCode).orElse(""),
                    item.getOperationType().equals(OperationType.CREDIT) ? item.getAmountFcy().negate() : item.getAmountFcy(),
                    item.getOperationType().equals(OperationType.CREDIT) ? item.getAmountLcy().negate() : item.getAmountLcy(),
                    item.getFxRate(),
                    item.getCostCenter().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode).orElse(""),
                    item.getCostCenter().flatMap(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getName).orElse(""),
                    itemCostCenter.map(costCenter -> costCenter.getParent().map(CostCenter::getParentCustomerCode).orElse("")).orElse(""),
                    itemCostCenter.map(costCenter -> costCenter.getParent().map(CostCenter::getName).orElse("")).orElse(""),
                    item.getProject().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode).orElse(""),
                    item.getProject().flatMap(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getName).orElse(""),
                    itemProject.map(Project::getParentCustomerCode).orElse(""),
                    itemProject.map(project -> project.getParent().map(Project::getName).orElse("")).orElse(""),
                    item.getAccountEvent().map(AccountEvent::getCode).orElse(""),
                    item.getAccountEvent().map(AccountEvent::getName).orElse(""),
                    item.getDocument().map(Document::getNum).orElse(""),
                    item.getDocument().map(document -> document.getCurrency().getCustomerCode()).orElse(""),
                    item.getDocument().flatMap(document -> document.getVat().map(Vat::getCustomerCode)).orElse(""),
                    item.getDocument().flatMap(document -> document.getVat().flatMap(Vat::getRate)).orElse(ZERO),
                    item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getCustomerCode)).orElse(""),
                    item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).isPresent() ? item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).get().toString() : "",
                    item.getDocument().flatMap(document -> document.getCounterparty().flatMap(Counterparty::getName)).orElse(""),
                    item.getRejection().map(Rejection::getRejectionReason).orElse(null)
            );
        }).collect(toSet());
    }

    private Set<ViolationView> getViolations(TransactionEntity transaction) {
        return transaction.getViolations().stream().map(violation -> new ViolationView(
                violation.getSeverity(),
                violation.getSource(),
                violation.getTxItemId(),
                violation.getCode(),
                violation.getBag()
        )).collect(toSet());
    }

    private TransactionReconciliationTransactionsView getReconciliationTransactionsSelector(Object[] violations) {
        for (Object o : violations) {
            if (Objects.isNull(o)) {
                continue;
            }
            if (o instanceof TransactionEntity transactionEntity && transactionEntity.getLastReconcilation().isPresent()) {
                return getTransactionReconciliationView(transactionEntity);
            }
            if (o instanceof ReconcilationViolation reconcilationViolation) {
                return getTransactionReconciliationViolationView(reconcilationViolation);
            }
        }
        return getTransactionReconciliationViolationView();
    }

    public Map<FilterOptions, List<String>> getFilterOptions(List<FilterOptions> filterOptions, String orgId) {
        Map<FilterOptions, List<String>> filterOptionsListMap = new EnumMap<>(FilterOptions.class);
        for(FilterOptions filterOption : filterOptions) {
            switch (filterOption) {
                case USERS -> filterOptionsListMap.put(filterOption, transactionBatchRepositoryGateway.findBatchUsersList(orgId));
                case DOCUMENT_NUMBERS ->  filterOptionsListMap.put(filterOption, transactionItemRepository.getAllDocumentNumbers());
                case TRANSACTION_TYPES -> filterOptionsListMap.put(filterOption, Arrays.stream(TransactionType.values()).map(Enum::name).toList());
            }
        }
        return filterOptionsListMap;
    }
}
