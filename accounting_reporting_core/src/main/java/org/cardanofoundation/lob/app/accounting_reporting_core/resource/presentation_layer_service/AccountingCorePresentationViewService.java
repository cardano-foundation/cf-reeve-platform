package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static java.math.BigDecimal.ZERO;
import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.FailureResponses.transactionNotFoundResponse;
import static org.cardanofoundation.lob.app.accounting_reporting_core.utils.PageableFieldMappings.TRANSACTION_ENTITY_FIELD_MAPPINGS;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opencsv.CSVWriter;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FilterOptions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ReconciliationStatisticDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionWithViolationDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.FilteringOptionsListResponse;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.ValidateIngestionResponseWaiter;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.AccountingCoreService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.repository.ProjectRepository;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;
import org.cardanofoundation.lob.app.support.javers.BagParser;
import org.cardanofoundation.lob.app.support.problem_support.IdentifiableProblem;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Service
@org.jmolecules.ddd.annotation.Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
// presentation layer service
public class AccountingCorePresentationViewService {

    private static final String INVALID_EXTRACTOR_TYPE_MSG = "Invalid extractorType '{}' for transaction {}";

    private final ValidateIngestionResponseWaiter validateIngestionResponseWaiter;

    private final TransactionRepositoryGateway transactionRepositoryGateway;
    private final AccountingCoreService accountingCoreService;
    private final TransactionBatchRepositoryGateway transactionBatchRepositoryGateway;
    private final TransactionReconcilationRepository transactionReconcilationRepository;
    private final CostCenterRepository costCenterRepository;
    private final ProjectRepository projectRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final ReconcilationRepository reconcilationRepository;
    private final AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    private static final Map<String, String> RV_FIELD_MAP =
            Map.of("id", "transactionId",
                    "internalNumber", "transactionInternalNumber",
                    "internalTransactionNumber", "transactionInternalNumber",
                    "entryDate", "transactionEntryDate",
                    "function('enum_to_text', transactionType)", "transactionType",
                    "totalAmountLcy", "amountLcySum"
            );

    // This function is to add dynamically sort for violations, since we are
    // querying two types at the same time for performance.
    // This function adds rv.<field> for each shared field in the sort if there is a
    // tr.<field>
    public Pageable expandSorts(Pageable pageable, boolean mapRv) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return pageable;
        }

        List<Sort.Order> newOrders = new ArrayList<>();

        pageable.getSort().forEach(order -> {
            String field = order.getProperty();

            // if field has an rv mapping → also add rv.<mappedField>
            if (RV_FIELD_MAP.containsKey(field) && mapRv) {
                String rvField = RV_FIELD_MAP.get(field);
                if (field.contains("function('enum_to_text', ")) {
                    rvField = field.replace("function('enum_to_text', ", "function('enum_to_text', rv.");
                    Sort newSort = JpaSort.unsafe(order.getDirection(),
                            rvField);
                    newOrders.add(newSort.iterator().next());
                } else {
                    newOrders.add(new Sort.Order(order.getDirection(),
                            "rv." + rvField));
                }
            }

            if (field.contains("function('enum_to_text', ")) {
                field = field.replace("function('enum_to_text', ", "function('enum_to_text', tr.");
                Sort newSort = JpaSort.unsafe(order.getDirection(), field);
                newOrders.add(newSort.iterator().next());
            } else {
                newOrders.add(new Sort.Order(order.getDirection(), "tr." + field));
            }

        });

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(newOrders));
    }

    public ReconciliationResponseView allReconciliationTransaction(
            ReconciliationFilterRequest body, Pageable pageable) {
        Object transactionsStatistic = reconcilationRepository
                .findCalcReconciliationStatistic();
        Optional<ReconcilationEntity> latestReconcilation =
                transactionReconcilationRepository.findTopByOrderByCreatedAtDesc();
        List<TransactionReconciliationTransactionsView> transactions;
        long count;
        Set<ReconcilationRejectionCode> rejectionCodes = body
                .getReconciliationRejectionCode().stream()
                .map(ReconciliationRejectionCodeRequest::toReconcilationRejectionCode)
                .collect(Collectors.toSet());
        if (body.getFilter().equals(ReconciliationFilterStatusRequest.UNRECONCILED)) {
            pageable = expandSorts(pageable, true);
            Page<TransactionWithViolationDto> allReconciliationSpecial =
                    reconcilationRepository.findAllReconciliationSpecial(
                            rejectionCodes.isEmpty() ? null
                                    : rejectionCodes,
                            body.getDateFrom().orElse(null),
                            body.getDateTo().orElse(null),
                            body.getSource().map(t -> t.name())
                                    .orElse(null),
                            body.getTransactionTypes().isEmpty() ? null
                                    : body.getTransactionTypes(),
                            body.getTransactionId(), pageable);
            count = allReconciliationSpecial.getTotalElements();
            transactions = allReconciliationSpecial.stream()
                    .map(this::getReconciliationTransactionsSelector)
                    .toList();
        } else {
            pageable = expandSorts(pageable, false);
            Page<TransactionEntity> pagedTransactions = reconcilationRepository
                    .findAllReconcilation(body.getFilter().name(),
                            body.getDateFrom().orElse(null),
                            body.getDateTo().orElse(null),
                            body.getTransactionTypes().isEmpty() ? null
                                    : body.getTransactionTypes(),
                            body.getTransactionId(), body.getSource().map(t -> t.name()).orElse(null),
                            pageable);
            count = pagedTransactions.getTotalElements();
            transactions = pagedTransactions.stream()
                    .map(this::getTransactionReconciliationView)
                    .toList();
        }
        return new ReconciliationResponseView(count,
                latestReconcilation.flatMap(ReconcilationEntity::getFrom),
                latestReconcilation.flatMap(ReconcilationEntity::getTo),
                latestReconcilation.map(reconcilationEntity -> reconcilationEntity
                        .getUpdatedAt().toLocalDate()),
                getTransactionReconciliationStatistic(transactionsStatistic),
                transactions);
    }

    public List<TransactionView> allTransactions(SearchRequest body) {
        List<TransactionEntity> transactions = transactionRepositoryGateway.findAllByStatus(
                body.getOrganisationId(), body.getStatus(),
                body.getTransactionType(),
                PageRequest.of(body.getPage(), body.getSize()));

        return transactions.stream().map(this::getTransactionView).sorted(
                        Comparator.comparing(TransactionView::getAmountTotalLcy).reversed())
                .toList();
    }

    public Optional<TransactionView> transactionDetailSpecific(String transactionId) {
        Optional<TransactionEntity> transactionEntity =
                transactionRepositoryGateway.findById(transactionId);

        return transactionEntity.map(this::getTransactionView);
    }


    public Either<ProblemDetail, Optional<BatchView>> batchDetail(String batchId,
                                                                  List<TransactionProcessingStatus> txStatus, Pageable page,
                                                                  BatchFilterRequest batchFilterRequest) {
        Either<ProblemDetail, Pageable> pageableEither =
                jpaSortFieldValidator.convertPageable(page, TRANSACTION_ENTITY_FIELD_MAPPINGS, TransactionEntity.class);
        if (pageableEither.isLeft()) {
            return Either.left(pageableEither.getLeft());
        }
        Pageable finalPage = pageableEither.get();
        return Either.right(transactionBatchRepositoryGateway.findById(batchId)
                .map(transactionBatchEntity -> {
                    Page<TransactionEntity> transactions = this.getTransaction(
                            transactionBatchEntity, txStatus, finalPage,
                            batchFilterRequest);

                    BatchStatisticsView statistic = BatchStatisticsView.from(
                            batchId,
                            transactionBatchEntity.getBatchStatistics()
                                    .orElse(new BatchStatistics()));
                    FilteringParametersView filteringParameters =
                            this.getFilteringParameters(
                                    transactionBatchEntity
                                            .getFilteringParameters());

                    return new BatchView(transactionBatchEntity.getId(),
                            transactionBatchEntity.getCreatedAt()
                                    .toString(),
                            transactionBatchEntity.getUpdatedAt()
                                    .toString(),
                            transactionBatchEntity.getCreatedBy(),
                            transactionBatchEntity.getUpdatedBy(),
                            transactionBatchEntity.getOrganisationId(),
                            transactionBatchEntity.getStatus(),
                            statistic, filteringParameters,
                            transactions.stream().map(
                                            this::getTransactionView)
                                    .toList(),
                            convertBagToJson(transactionBatchEntity.getDetails()
                                    .orElse(Details.builder()
                                            .build()))
                                    .getBag(),
                            transactions.getTotalElements());
                }));
    }

    public Either<ProblemDetail, BatchsDetailView> listAllBatch(BatchSearchRequest body, Pageable pageable) {
        BatchsDetailView batchDetailView = new BatchsDetailView();
        Page<TransactionBatchEntity> transactionBatchEntities =
                transactionBatchRepositoryGateway.findByFilter(body, pageable);
        List<BatchView> batches =
                transactionBatchEntities.stream().map(transactionBatchEntity -> {
                    BatchStatisticsView statistic = BatchStatisticsView.from(
                            transactionBatchEntity.getId(),
                            transactionBatchEntity.getBatchStatistics()
                                    .orElse(new BatchStatistics()));
                    return new BatchView(transactionBatchEntity.getId(),
                            transactionBatchEntity.getCreatedAt()
                                    .toString(),
                            transactionBatchEntity.getUpdatedAt()
                                    .toString(),
                            transactionBatchEntity.getCreatedBy(),
                            transactionBatchEntity.getUpdatedBy(),
                            transactionBatchEntity.getOrganisationId(),
                            transactionBatchEntity.getStatus(),
                            statistic,
                            this.getFilteringParameters(
                                    transactionBatchEntity
                                            .getFilteringParameters()),
                            List.of(),
                            convertBagToJson(transactionBatchEntity.getDetails()
                                    .orElse(Details.builder()
                                            .build()))
                                    .getBag(),
                            null // transactions are not loaded here
                    );
                }).toList();

        batchDetailView.setBatchs(batches);
        batchDetailView.setTotal(transactionBatchEntities.getTotalElements());

        return Either.right(batchDetailView);
    }

    @Transactional
    public Either<ProblemDetail, Void> extractionTrigger(ExtractionRequest body) {
        UserExtractionParameters fp = getUserExtractionParameters(body);

        return accountingCoreService.scheduleIngestion(fp, body.getExtractorType(),
                Optional.ofNullable(body.getFile()), body.getParameters());
    }

    private UserExtractionParameters getUserExtractionParameters(ExtractionRequest body) {
        ArrayList<String> transactionNumbers =
                new ArrayList<>(body.getTransactionNumbers());
        transactionNumbers.removeIf(String::isEmpty);

        return UserExtractionParameters.builder()
                .from(body.getDateFrom().isEmpty() ? LocalDate.EPOCH
                        : LocalDate.parse(body.getDateFrom()))
                .to(body.getDateTo().isEmpty() ? LocalDate.now()
                        : LocalDate.parse(body.getDateTo()))
                .organisationId(body.getOrganisationId())
                .transactionTypes(body.getTransactionType())
                .transactionNumbers(transactionNumbers).build();
    }

    public Either<List<ProblemDetail>, Void> extractionValidation(ExtractionRequest body) {
        UserExtractionParameters userExtractionParameters =
                getUserExtractionParameters(body);
        return accountingCoreService.validateIngestion(userExtractionParameters,
                body.getExtractorType(), Optional.ofNullable(body.getFile()),
                body.getParameters());
    }

    @Transactional
    public List<TransactionProcessView> approveTransactions(
            TransactionsRequest transactionsRequest) {
        return transactionRepositoryGateway.approveTransactions(transactionsRequest)
                .stream()
                .map(txEntityE -> txEntityE.fold(
                        txProblem -> TransactionProcessView.createFail(
                                txProblem.getId(),
                                txProblem.getProblem()),
                        success -> TransactionProcessView
                                .createSuccess(success.getId())))
                .toList();
    }

    @Transactional
    public List<TransactionProcessView> approveTransactionsPublish(
            TransactionsRequest transactionsRequest) {
        return transactionRepositoryGateway.approveTransactionsDispatch(transactionsRequest)
                .stream()
                .map(txEntityE -> txEntityE.fold(
                        txProblem -> TransactionProcessView.createFail(
                                txProblem.getId(),
                                txProblem.getProblem()),
                        success -> TransactionProcessView
                                .createSuccess(success.getId())))
                .toList();
    }

    @Transactional
    public TransactionItemsProcessRejectView rejectTransactionItems(
            TransactionItemsRejectionRequest transactionItemsRejectionRequest) {
        Optional<TransactionEntity> txM = transactionRepositoryGateway
                .findById(transactionItemsRejectionRequest.getTransactionId());
        if (txM.isEmpty()) {
            Either<IdentifiableProblem, TransactionEntity> errorE =
                    transactionNotFoundResponse(transactionItemsRejectionRequest
                            .getTransactionId());
            return TransactionItemsProcessRejectView.createFail(
                    transactionItemsRejectionRequest.getTransactionId(),
                    errorE.getLeft().getProblem());

        }
        TransactionEntity tx = txM.orElseThrow();
        Set<TransactionItemsProcessView> items = transactionRepositoryGateway
                .rejectTransactionItems(tx,
                        transactionItemsRejectionRequest
                                .getTransactionItemsRejections())
                .stream()
                .map(txItemEntityE -> txItemEntityE.fold(
                        txProblem -> TransactionItemsProcessView.createFail(
                                txProblem.getId(),
                                txProblem.getProblem()),
                        success -> TransactionItemsProcessView
                                .createSuccess(success.getId())))
                .collect(toSet());

        return TransactionItemsProcessRejectView.createSuccess(tx.getId(),
                tx.getProcessingStatus(), items);
    }

    @Transactional
    public BatchReprocessView scheduleReIngestionForFailed(String batchId) {
        Either<ProblemDetail, Void> txM =
                accountingCoreService.scheduleReIngestionForFailed(batchId);

        if (txM.isEmpty()) {
            return BatchReprocessView.createFail(batchId, txM.getLeft());

        }

        return BatchReprocessView.createSuccess(batchId);
    }

    private FilteringParametersView getFilteringParameters(
            FilteringParameters filteringParameters) {
        return new FilteringParametersView(filteringParameters.getTransactionTypes(),
                filteringParameters.getFrom(), filteringParameters.getTo(),
                filteringParameters.getAccountingPeriodFrom(),
                filteringParameters.getAccountingPeriodTo(),
                filteringParameters.getTransactionNumbers());
    }

    private Page<TransactionEntity> getTransaction(
            TransactionBatchEntity transactionBatchEntity,
            List<TransactionProcessingStatus> status, Pageable pageable,
            BatchFilterRequest batchFilterRequest) {
        if(batchFilterRequest == null) {
            return accountingCoreTransactionRepository.findAllByBatchId(
                    Optional.ofNullable(transactionBatchEntity).map(TransactionBatchEntity::getId).orElse(null),
                    status, pageable);
        } else {
            return accountingCoreTransactionRepository.findAllByBatchId(
                    Optional.ofNullable(transactionBatchEntity).map(TransactionBatchEntity::getId).orElse(null),
                    status,
                    batchFilterRequest.getInternalTransactionNumber(),
                    batchFilterRequest.getTransactionTypes(),
                    batchFilterRequest.getDocumentNumbers(),
                    batchFilterRequest.getDocumentNumber(),
                    batchFilterRequest.getCurrencyCustomerCodes(),
                    batchFilterRequest.getMinFCY(), batchFilterRequest.getMaxFCY(),
                    batchFilterRequest.getMinLCY(), batchFilterRequest.getMaxLCY(),
                    batchFilterRequest.getMinTotalLcy(),
                    batchFilterRequest.getMaxTotalLcy(),
                    Optional.ofNullable(batchFilterRequest.getDateFrom())
                            .orElse(LocalDate.of(1970, 1, 1)).atStartOfDay(),
                    Optional.ofNullable(batchFilterRequest.getDateTo())
                            .orElse(LocalDate.now()).atStartOfDay(),
                    batchFilterRequest.getVatCustomerCodes(),
                    batchFilterRequest.getParentCostCenterCustomerCodes(),
                    batchFilterRequest.getCostCenterCustomerCodes(),
                    batchFilterRequest.getCounterPartyCustomerCodes(),
                    batchFilterRequest.getCounterPartyTypes(),
                    batchFilterRequest.getDebitAccountCodes(),
                    batchFilterRequest.getCreditAccountCodes(),
                    batchFilterRequest.getEventCodes(),
                    batchFilterRequest.getProjectCustomerCodes(),
                    batchFilterRequest.getParentProjectCustomerCodes(),
                    pageable);
        }
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationView(
            TransactionEntity transactionEntity) {
        DataSourceView dataSourceView = DataSourceView.UNKNOWN;
        try {
            dataSourceView = DataSourceView
                    .valueOf(transactionEntity.getExtractorType());
        } catch (IllegalArgumentException e) {
            log.warn(INVALID_EXTRACTOR_TYPE_MSG,
                    transactionEntity.getExtractorType(),
                    transactionEntity.getId(), e);
        }
        return new TransactionReconciliationTransactionsView(transactionEntity.getId(),
                transactionEntity.getInternalTransactionNumber(),
                transactionEntity.getBatchId(),
                transactionEntity.getEntryDate(),
                transactionEntity.getTransactionType(), dataSourceView,
                Optional.of(transactionEntity.getOverallStatus()),
                transactionEntity.getProcessingStatus(),
                Optional.of(transactionEntity.getAutomatedValidationStatus()),
                transactionEntity.getTransactionApproved(),
                transactionEntity.getLedgerDispatchApproved(),
                transactionEntity.getTotalAmountLcy(), false, // TODO Hard coded
                // value?
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getSource().map(
                                        TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getSink().map(
                                        TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getFinalStatus().map(
                                        TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),

                transactionEntity.getLastReconcilation()
                        .map(reconcilationEntity -> reconcilationEntity
                                .getViolations().stream()
                                .filter(reconcilationViolation -> reconcilationViolation
                                        .getTransactionId()
                                        .equals(transactionEntity
                                                .getId()))
                                .map(reconcilationViolation -> ReconciliationRejectionCodeRequest
                                        .of(reconcilationViolation
                                                        .getRejectionCode(),
                                                transactionEntity
                                                        .getLedgerDispatchApproved()))
                                .collect(toSet()))
                        .orElse(new LinkedHashSet<>()),
                transactionEntity.getLastReconcilation()
                        .map(CommonEntity::getCreatedAt).orElse(null),
                getTransactionItemView(transactionEntity),
                getViolations(transactionEntity),
                transactionEntity.getLastReconcilation()
                        .map(reconcilationEntity -> reconcilationEntity
                                .getViolations().stream()
                                .filter(reconcilationViolation -> reconcilationViolation
                                        .getTransactionId()
                                        .equals(transactionEntity
                                                .getId()))
                                .map(reconcilationViolation -> {
                                    return reconcilationViolation.getSourceDiff().isPresent() ? reconcilationViolation.getSourceDiff().get() : null;
                                })
                                .collect(toSet()))
                        .orElse(new LinkedHashSet<>())


        );
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationView(
            TransactionEntity transactionEntity, ReconcilationViolation specificViolation) {
        DataSourceView dataSourceView = DataSourceView.UNKNOWN;
        try {
            dataSourceView = DataSourceView
                    .valueOf(transactionEntity.getExtractorType());
        } catch (IllegalArgumentException e) {
            log.warn(INVALID_EXTRACTOR_TYPE_MSG,
                    transactionEntity.getExtractorType(),
                    transactionEntity.getId(), e);
        }
        return new TransactionReconciliationTransactionsView(transactionEntity.getId(),
                transactionEntity.getInternalTransactionNumber(),
                transactionEntity.getBatchId(),
                transactionEntity.getEntryDate(),
                transactionEntity.getTransactionType(), dataSourceView,
                Optional.of(transactionEntity.getOverallStatus()),
                transactionEntity.getProcessingStatus(),
                Optional.of(transactionEntity.getAutomatedValidationStatus()),
                transactionEntity.getTransactionApproved(),
                transactionEntity.getLedgerDispatchApproved(),
                transactionEntity.getTotalAmountLcy(), false,
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getSource().map(
                                        TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getSink().map(
                                        TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getFinalStatus().map(
                                        TransactionReconciliationTransactionsView.ReconciliationCodeView::of))
                        .orElse(TransactionReconciliationTransactionsView.ReconciliationCodeView.NEVER),
                Optional.ofNullable(specificViolation)
                        .map(v -> (Set<ReconciliationRejectionCodeRequest>) new LinkedHashSet<>(Set.of(
                                ReconciliationRejectionCodeRequest.of(v.getRejectionCode(),
                                        transactionEntity.getLedgerDispatchApproved()))))
                        .orElse(new LinkedHashSet<>()),
                transactionEntity.getLastReconcilation()
                        .map(CommonEntity::getCreatedAt).orElse(null),
                getTransactionItemView(transactionEntity),
                getViolations(transactionEntity),
                Optional.ofNullable(specificViolation)
                        .flatMap(ReconcilationViolation::getSourceDiff)
                        .map(diff -> (Set<String>) new LinkedHashSet<>(Set.of(diff)))
                        .orElse(new LinkedHashSet<>())
        );
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationViolationView(
            ReconcilationViolation reconcilationViolation, LocalDateTime lastReconciledDate) {
        return new TransactionReconciliationTransactionsView(
                reconcilationViolation.getTransactionId(),
                reconcilationViolation.getTransactionInternalNumber(),
                null,
                reconcilationViolation.getTransactionEntryDate(),
                reconcilationViolation.getTransactionType(),
                DataSourceView.NETSUITE, Optional.empty(), Optional.empty(),
                Optional.empty(), false, false,
                reconcilationViolation.getAmountLcySum(), false,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                Set.of(ReconciliationRejectionCodeRequest.of(
                        reconcilationViolation.getRejectionCode(), false)),
                lastReconciledDate, new LinkedHashSet<>(), new LinkedHashSet<>(),
                reconcilationViolation.getSourceDiff().stream().collect(toSet())
        );
    }

    private TransactionReconciliationTransactionsView getTransactionReconciliationViolationView() {
        return new TransactionReconciliationTransactionsView("", "", null, LocalDate.now(),
                TransactionType.CardCharge, DataSourceView.NETSUITE,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                BigDecimal.valueOf(123), false,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                TransactionReconciliationTransactionsView.ReconciliationCodeView.NOK,
                new LinkedHashSet<>(), null, null, null, null

        );
    }

    private TransactionView getTransactionView(TransactionEntity transactionEntity) {
        DataSourceView dataSourceView = DataSourceView.UNKNOWN;
        try {
            dataSourceView = DataSourceView
                    .valueOf(transactionEntity.getExtractorType());
        } catch (IllegalArgumentException e) {
            log.warn(INVALID_EXTRACTOR_TYPE_MSG,
                    transactionEntity.getExtractorType(),
                    transactionEntity.getId(), e);
        }
        return new TransactionView(transactionEntity.getId(),
                transactionEntity.getInternalTransactionNumber(),
                transactionEntity.getEntryDate(),
                transactionEntity.getTransactionType(), dataSourceView,
                transactionEntity.getOverallStatus(),
                transactionEntity.getProcessingStatus(),
                transactionEntity.getLedgerDispatchStatusErrorReason(),
                transactionEntity.getAutomatedValidationStatus(),
                transactionEntity.getLedgerDispatchStatus(),
                transactionEntity.getTransactionApproved(),
                transactionEntity.getLedgerDispatchApproved(),
                transactionEntity.getTotalAmountLcy(),
                transactionEntity.hasAnyRejection(),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getSource().map(
                                        TransactionView.ReconciliationCodeView::of))
                        .orElse(TransactionView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getSink().map(
                                        TransactionView.ReconciliationCodeView::of))
                        .orElse(TransactionView.ReconciliationCodeView.NEVER),
                transactionEntity.getReconcilation().flatMap(
                                reconcilation -> reconcilation.getFinalStatus().map(
                                        TransactionView.ReconciliationCodeView::of))
                        .orElse(TransactionView.ReconciliationCodeView.NEVER),

                transactionEntity.getLastReconcilation()
                        .map(reconcilationEntity -> reconcilationEntity
                                .getViolations().stream()
                                .filter(reconcilationViolation -> reconcilationViolation
                                        .getTransactionId()
                                        .equals(transactionEntity
                                                .getId()))
                                .map(reconcilationViolation -> ReconciliationRejectionCodeRequest
                                        .of(reconcilationViolation
                                                        .getRejectionCode(),
                                                transactionEntity
                                                        .getLedgerDispatchApproved()))
                                .collect(toSet()))
                        .orElse(new LinkedHashSet<>()),
                transactionEntity.getLastReconcilation()
                        .map(CommonEntity::getCreatedAt).orElse(null),
                transactionEntity.getItemCount(),
                getTransactionItemView(transactionEntity),
                getViolations(transactionEntity)

        );
    }

    private TransactionReconciliationStatisticView getTransactionReconciliationStatistic(
            Object transactionsStatistic) {

        Object[] result = (Object[]) transactionsStatistic;
        // TODO we need to find a better solution than handling these object arrays
        return new TransactionReconciliationStatisticView(
                (Integer) ((Long) result[0]).intValue(),
                (Integer) ((Long) result[1]).intValue(),
                (Integer) ((Long) result[2]).intValue(),
                (Integer) ((Long) result[3]).intValue(),
                (Integer) ((Long) result[4]).intValue(),
                (Integer) ((Long) result[5]).intValue(),
                (Long) result[6],
                (Integer) ((Long) result[7]).intValue(),
                (Long) result[8],
                ((Long) result[6]).intValue()
                        + ((Long) result[7]).intValue()
                        + ((Long) result[8]).intValue());
    }

    private Set<TransactionItemView> getTransactionItemView(TransactionEntity transaction) {
        return transaction.getItems().stream().map(item -> {
            Optional<CostCenter> itemCostCenter = Optional.empty();
            Optional<Project> itemProject = Optional.empty();
            if (transaction.getOrganisation() != null) {
                itemCostCenter = costCenterRepository.findById(new CostCenter.Id(
                        transaction.getOrganisation().getId(),
                        item.getCostCenter().map(
                                        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode)
                                .orElse("")));
                itemProject = projectRepository.findById(new Project.Id(
                        transaction.getOrganisation().getId(),
                        item.getProject().map(
                                        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode)
                                .orElse("")));
            }
            return new TransactionItemView(item.getId(),
                    item.getAccountDebit().map(Account::getCode).orElse(""),
                    item.getAccountDebit().flatMap(Account::getName).orElse(""),
                    item.getAccountDebit().flatMap(Account::getRefCode)
                            .orElse(""),
                    item.getAccountCredit().map(Account::getCode).orElse(""),
                    item.getAccountCredit().flatMap(Account::getName)
                            .orElse(""),
                    item.getAccountCredit().flatMap(Account::getRefCode)
                            .orElse(""),
                    item.getOperationType().equals(OperationType.CREDIT)
                            ? item.getAmountFcy().negate()
                            : item.getAmountFcy(),
                    item.getOperationType().equals(OperationType.CREDIT)
                            ? item.getAmountLcy().negate()
                            : item.getAmountLcy(),
                    item.getFxRate(),
                    item.getCostCenter().map(
                                    org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode)
                            .orElse(""),
                    item.getCostCenter().flatMap(
                                    org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getName)
                            .orElse(""),
                    itemCostCenter.map(costCenter -> costCenter
                            .getParentCustomerCode()).orElse(""),
                    itemCostCenter.map(costCenter -> costCenter.getParent()
                                    .map(CostCenter::getName).orElse(""))
                            .orElse(""),
                    item.getProject().map(
                                    org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode)
                            .orElse(""),
                    item.getProject().flatMap(
                                    org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getName)
                            .orElse(""),
                    itemProject.map(Project::getParentCustomerCode).orElse(""),
                    itemProject.map(project -> project.getParent()
                                    .map(Project::getName).orElse(""))
                            .orElse(""),
                    item.getAccountEvent().map(AccountEvent::getCode)
                            .orElse(""),
                    item.getAccountEvent().map(AccountEvent::getName)
                            .orElse(""),
                    item.getDocument().map(Document::getNum).orElse(""),
                    item.getDocument()
                            .map(document -> document.getCurrency()
                                    .getCustomerCode())
                            .orElse(""),
                    item.getDocument()
                            .flatMap(document -> document.getVat()
                                    .map(Vat::getCustomerCode))
                            .orElse(""),
                    item.getDocument()
                            .flatMap(document -> document.getVat()
                                    .flatMap(Vat::getRate))
                            .orElse(ZERO),
                    item.getDocument().flatMap(d -> d.getCounterparty()
                                    .map(Counterparty::getCustomerCode))
                            .orElse(""),
                    item.getDocument()
                            .flatMap(d -> d.getCounterparty()
                                    .map(Counterparty::getType))
                            .isPresent() ? item.getDocument().flatMap(d -> d.getCounterparty().map(Counterparty::getType)).get().toString() : "",
                    item.getDocument().flatMap(document -> document
                            .getCounterparty()
                            .flatMap(Counterparty::getName)).orElse(""),
                    item.getRejection().map(Rejection::getRejectionReason)
                            .orElse(null));
        }).collect(toSet());
    }

    private Set<ViolationView> getViolations(TransactionEntity transaction) {
        return transaction.getViolations().stream()
                .map(violation -> new ViolationView(violation.getSeverity(),
                        violation.getSource(), violation.getTxItemId(),
                        violation.getCode(), violation.getBag()))
                .collect(toSet());
    }

    private TransactionReconciliationTransactionsView getReconciliationTransactionsSelector(
            TransactionWithViolationDto violations) {
        // fallback, if the transaction doesn't exist in Reeve
        if (Optional.ofNullable(violations.tx()).isPresent() && violations.tx().getReconcilation().isPresent()) {
            return getTransactionReconciliationView(violations.tx(), violations.violation());
        }

        if (Optional.ofNullable(violations.violation()).isPresent()) {
            return getTransactionReconciliationViolationView(violations.violation(), violations.lastReconciledDate());
        }
        return getTransactionReconciliationViolationView();
    }

    public Map<String, ReconciliationStatisticView> getReconciliationStatisticByDateRange(ReconciliationStatisticRequest request) {
        List<ReconciliationStatisticDto> rows = reconcilationRepository.findReconciliationStatisticByDateRange(
                        request.getOrganisationId(),
                        request.getDateFrom(),
                        request.getDateTo())
                .stream()
                .map(p -> new ReconciliationStatisticDto(
                        p.getYear(),
                        p.getMonth(),
                        p.getReconciledCount(),
                        p.getUnreconciledCount()))
                .toList();

        IntervalType aggregate = request.getAggregate();
        if (aggregate == null) {
            return aggregateTotal(rows);
        }

        boolean multiYear = spansMultipleYears(rows);

        return switch (aggregate) {
            case MONTH -> aggregateByMonth(rows, multiYear);
            case QUARTER -> aggregateByQuarter(rows, multiYear);
            case YEAR -> aggregateByYear(rows);
        };
    }

    private boolean spansMultipleYears(List<ReconciliationStatisticDto> rows) {
        if (rows.isEmpty()) {
            return false;
        }
        int firstYear = rows.getFirst().year();
        int lastYear = rows.getLast().year();
        return firstYear != lastYear;
    }

    private Map<String, ReconciliationStatisticView> aggregateTotal(List<ReconciliationStatisticDto> rows) {
        long totalReconciled = 0;
        long totalUnreconciled = 0;
        for (ReconciliationStatisticDto row : rows) {
            totalReconciled += row.reconciledCount();
            totalUnreconciled += row.unreconciledCount();
        }
        Map<String, ReconciliationStatisticView> result = new LinkedHashMap<>();
        result.put("STATISTICS", new ReconciliationStatisticView(totalReconciled, totalUnreconciled));
        return result;
    }

    private Map<String, ReconciliationStatisticView> aggregateByMonth(List<ReconciliationStatisticDto> rows, boolean multiYear) {
        Map<String, ReconciliationStatisticView> result = new LinkedHashMap<>();
        for (ReconciliationStatisticDto row : rows) {
            int year = row.year();
            int month = row.month();
            long reconciled = row.reconciledCount();
            long unreconciled = row.unreconciledCount();

            String key = Month.of(month).name();
            if (multiYear) {
                key = key + "_" + year;
            }
            result.put(key, new ReconciliationStatisticView(reconciled, unreconciled));
        }
        return result;
    }

    private Map<String, ReconciliationStatisticView> aggregateByQuarter(List<ReconciliationStatisticDto> rows, boolean multiYear) {
        Map<String, long[]> quarterMap = new LinkedHashMap<>();
        for (ReconciliationStatisticDto row : rows) {
            int year = row.year();
            int month = row.month();
            int quarter = (month - 1) / 3 + 1;

            String key = "Q" + quarter;
            if (multiYear) {
                key = key + "_" + year;
            }
            long[] counts = quarterMap.computeIfAbsent(key, k -> new long[2]);
            counts[0] += row.reconciledCount();
            counts[1] += row.unreconciledCount();
        }

        Map<String, ReconciliationStatisticView> result = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : quarterMap.entrySet()) {
            result.put(entry.getKey(), new ReconciliationStatisticView(entry.getValue()[0], entry.getValue()[1]));
        }
        return result;
    }

    private Map<String, ReconciliationStatisticView> aggregateByYear(List<ReconciliationStatisticDto> rows) {
        Map<String, long[]> yearMap = new LinkedHashMap<>();
        for (ReconciliationStatisticDto row : rows) {
            int year = row.year();
            String key = String.valueOf(year);
            long[] counts = yearMap.computeIfAbsent(key, k -> new long[2]);
            counts[0] += row.reconciledCount();
            counts[1] += row.unreconciledCount();
        }

        Map<String, ReconciliationStatisticView> result = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : yearMap.entrySet()) {
            result.put(entry.getKey(), new ReconciliationStatisticView(entry.getValue()[0], entry.getValue()[1]));
        }
        return result;
    }

    public Map<FilterOptions, List<FilteringOptionsListResponse>> getFilterOptions(
            List<FilterOptions> filterOptions, String orgId) {
        Map<FilterOptions, List<FilteringOptionsListResponse>> filterOptionsListMap =
                new EnumMap<>(FilterOptions.class);
        for (FilterOptions filterOption : filterOptions) {
            switch (filterOption) {

                case USERS -> filterOptionsListMap.put(filterOption,
                        transactionBatchRepositoryGateway
                                .findBatchUsersList(orgId).stream()
                                .map(user -> FilteringOptionsListResponse
                                        .builder()
                                        .name(user)
                                        .description(user)
                                        .build())
                                .toList());

                case DOCUMENT_NUMBERS -> filterOptionsListMap.put(filterOption,
                        transactionItemRepository.getAllDocumentNumbers()
                                .stream()
                                .map(document -> FilteringOptionsListResponse
                                        .builder()
                                        .name(document)
                                        .description(document)
                                        .build())
                                .toList());

                case TRANSACTION_NUMBERS -> filterOptionsListMap.put(filterOption,
                        accountingCoreTransactionRepository
                                .findAllTransactionNumbers(orgId)
                                .stream()
                                .map(transactionNumber -> FilteringOptionsListResponse
                                        .builder()
                                        .name(transactionNumber)
                                        .description(transactionNumber)
                                        .build())
                                .toList());

                case TRANSACTION_TYPES -> filterOptionsListMap.put(filterOption,
                        Arrays.stream(TransactionType.values()).map(
                                        type -> FilteringOptionsListResponse
                                                .builder()
                                                .name(type.name())
                                                .description(type
                                                        .name())
                                                .build())
                                .toList());

                case COUNTER_PARTY_NAMES -> filterOptionsListMap.put(filterOption,
                        transactionItemRepository.getAllCounterParty(orgId)
                                .stream()
                                .map(document -> FilteringOptionsListResponse
                                        .builder()
                                        .name(document
                                                .get("name"))
                                        .description(document
                                                .get("name"))
                                        .build())
                                .toList());

                case COUNTER_PARTY -> filterOptionsListMap.put(filterOption,
                        transactionItemRepository.getAllCounterParty(orgId)
                                .stream()
                                .map(document -> FilteringOptionsListResponse
                                        .builder()
                                        .customerCode(document
                                                .get("customerCode"))
                                        .description(document
                                                .get("name"))
                                        .build())
                                .toList());

                case COUNTER_PARTY_TYPE -> filterOptionsListMap.put(filterOption,
                        Arrays.stream(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type
                                        .values())
                                .map(type -> FilteringOptionsListResponse
                                        .builder()
                                        .name(type.name())
                                        .description(type
                                                .name())
                                        .build())
                                .toList());

                case RECONCILIATION_REJECTION_CODES -> filterOptionsListMap.put(filterOption,
                        Arrays.stream(ReconciliationRejectionCodeRequest.values())
                                .map(type -> FilteringOptionsListResponse
                                        .builder()
                                        .name(type.name())
                                        .description(type
                                                .name())
                                        .build())
                                .toList());
                case RECONCILIATION_SOURCES -> filterOptionsListMap.put(filterOption,
                        Arrays.stream(ReconciliationFilterSource.values())
                                .map(type -> FilteringOptionsListResponse
                                        .builder()
                                        .name(type.name())
                                        .build())
                                .toList());


            }
        }
        return filterOptionsListMap;
    }

    private Details convertBagToJson(Details details) {
        details.setBag(BagParser.parse(details.getBag()));
        return details;

    }

    public void downloadCsvTransactions(@Valid String orgId, String batchId, List<TransactionProcessingStatus> txStatus, BatchFilterRequest batchFilterRequest, OutputStream outputStream) {
        TransactionBatchEntity transactionBatchEntity = transactionBatchRepositoryGateway.findById(Optional.ofNullable(batchId).orElse("")).orElse(null);
        Page<TransactionEntity> transactions = this.getTransaction(
                transactionBatchEntity, txStatus, Pageable.unpaged(),
                batchFilterRequest);
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Transaction Number",
                    "Transaction Date",
                    "Transaction Type",
                    "Fx Rate",
                    "AmountLCY Debit",
                    "AmountLCY Credit",
                    "AmountFCY Debit",
                    "AmountFCY Credit",
                    "Debit Code",
                    "Debit Name",
                    "Credit Code",
                    "Credit Name",
                    "Event code",
                    "Project Code",
                    "Parent Project Code",
                    "Document Name",
                    "Currency",
                    "VAT Rate",
                    "VAT Code",
                    "Cost Center Code",
                    "Parent Cost Center Code",
                    "Counterparty Code",
                    "Counterparty Name",
                    "Counterparty Type",
                    "Extractor Type",
                    "Processing Status",
                    "Blockchain Hash"};
            csvWriter.writeNext(header, false);
            for (TransactionEntity transactionEntity : transactions) {
                for (TransactionItemEntity item : transactionEntity.getItems()) {
                    boolean isCredit = item.getOperationType().equals(OperationType.CREDIT);
                    Optional<CostCenter> parentCostcenter = item.getCostCenter().flatMap(costCenter -> costCenterRepository.findById(new CostCenter.Id(transactionEntity.getOrganisation().getId(), costCenter.getCustomerCode())));
                    Optional<Project> parentProject = item.getProject().flatMap(project -> projectRepository.findById(new Project.Id(transactionEntity.getOrganisation().getId(), project.getCustomerCode())));
                    String[] data = {
                            transactionEntity.getInternalTransactionNumber(),
                            transactionEntity.getEntryDate().toString(),
                            Optional.ofNullable(transactionEntity.getTransactionType()).map(Enum::name).orElse(""),
                            item.getFxRate().stripTrailingZeros().toPlainString(),
                            isCredit ? "" : item.getAmountLcy().stripTrailingZeros().toPlainString(),
                            isCredit ? item.getAmountLcy().stripTrailingZeros().toPlainString() : "",
                            isCredit ? "" : item.getAmountFcy().stripTrailingZeros().toPlainString(),
                            isCredit ? item.getAmountFcy().stripTrailingZeros().toPlainString() : "",
                            item.getAccountDebit().map(Account::getCode).orElse(""),
                            item.getAccountDebit().flatMap(Account::getName).orElse(""),
                            item.getAccountCredit().map(Account::getCode).orElse(""),
                            item.getAccountCredit().flatMap(Account::getName).orElse(""),
                            item.getAccountEvent().map(AccountEvent::getCode).orElse(""),
                            item.getProject().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project::getCustomerCode).orElse(""),
                            parentProject.map(Project::getParentCustomerCode).orElse(""),
                            item.getDocument().map(Document::getNum).orElse(""),
                            item.getDocument().map(document -> document.getCurrency().getCustomerCode()).orElse(""),
                            item.getDocument().flatMap(document -> document.getVat().map(Vat::getRate)).orElse(Optional.ofNullable(ZERO)).map(bigDecimal -> bigDecimal.stripTrailingZeros().toPlainString()).orElse(""),
                            item.getDocument().flatMap(document -> document.getVat().map(Vat::getCustomerCode)).orElse(""),
                            item.getCostCenter().map(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter::getCustomerCode).orElse(""),
                            parentCostcenter.map(CostCenter::getParentCustomerCode).orElse(""),
                            item.getDocument().flatMap(document -> document.getCounterparty().map(Counterparty::getCustomerCode)).orElse(""),
                            item.getDocument().flatMap(document -> document.getCounterparty().map(Counterparty::getName)).orElse(Optional.of("")).orElse(""),
                            item.getDocument().flatMap(document -> document.getCounterparty().map(counterparty -> counterparty.getType().name())).orElse(""),
                            transactionEntity.getExtractorType(),
                            mapTransactionProcessingStatusToString(transactionEntity.getProcessingStatus()),
                            transactionEntity.getLedgerDispatchReceipt().map(LedgerDispatchReceipt::getPrimaryBlockchainHash).orElse("")
                    };

                    csvWriter.writeNext(data, false);
                }
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing transactions to CSV for orgId {}: {}", orgId, e.getMessage(), e);
        }
    }

    private String mapTransactionProcessingStatusToString(Optional<TransactionProcessingStatus> status) {
        if (status.isEmpty()) {
            return "Unknown";
        }

        return switch (status.get()) {
            case APPROVE -> "Ready to Approve";
            case PENDING -> "Pending";
            case INVALID -> "Invalid";
            case PUBLISH -> "Ready to Publish";
            case PUBLISHED -> "Published";
            case DISPATCHED -> "Dispatched";
            case ROLLBACK -> "Rollback";
        };
    }

}
