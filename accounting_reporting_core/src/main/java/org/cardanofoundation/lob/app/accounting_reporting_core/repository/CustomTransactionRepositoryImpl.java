package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReconciliationRejectionCodeRequest;

@RequiredArgsConstructor
@Slf4j
public class CustomTransactionRepositoryImpl implements CustomTransactionRepository {

    private final EntityManager em;

    @Override
    public List<TransactionEntity> findAllByStatus(String organisationId,
                                                   List<TxValidationStatus> validationStatuses,
                                                   List<TransactionType> transactionTypes,
                                                   Pageable pageable) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<TransactionEntity> criteriaQuery = builder.createQuery(TransactionEntity.class);

        Root<TransactionEntity> rootEntry = criteriaQuery.from(TransactionEntity.class);
        Predicate pValidationStatuses = builder.isTrue(builder.literal(true));
        Predicate pOrganisationId = builder.equal(rootEntry.get("organisation").get("id"), organisationId);
        Predicate pTransactionType = builder.isTrue(builder.literal(true));

        if (!validationStatuses.isEmpty()) {
            pValidationStatuses = builder.in(rootEntry.get("automatedValidationStatus")).value(validationStatuses);
        }

        criteriaQuery.select(rootEntry);
        criteriaQuery.where(pValidationStatuses, pOrganisationId, pTransactionType);
        return em.createQuery(criteriaQuery).getResultList();
    }

    public Object findCalcReconciliationStatistic() {
        String missingInERP = "select count(missingInERP) from ( " +
                "SELECT rv.transactionId missingInERP " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id or tr.lastReconcilation IS NULL) AND tr.ledgerDispatchApproved IS TRUE " +
                "AND rv.rejectionCode = '" + ReconcilationRejectionCode.TX_NOT_IN_ERP + "' " +
                "GROUP BY rv.transactionId " +
                ") ";

        String newInERP = "select count(newInERP) from ( " +
                "SELECT rv.transactionId newInERP " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id or tr.lastReconcilation IS NULL)  " +
                "AND rv.rejectionCode = '" + ReconcilationRejectionCode.TX_NOT_IN_LOB + "' " +
                "GROUP BY rv.transactionId " +
                ") ";

        String inProcessing = "select count(inProcessing) from ( " +
                "SELECT rv.transactionId inProcessing " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id or tr.lastReconcilation IS NULL)  " +
                "AND rv.rejectionCode = '" + ReconcilationRejectionCode.SINK_RECONCILATION_FAIL + "' " +
                "GROUP BY rv.transactionId " +
                ") ";

        String newVersionNotPublished = "select count(newVersionNotPublished) from ( " +
                "SELECT rv.transactionId newVersionNotPublished " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id or tr.lastReconcilation IS NULL)  " +
                "AND rv.rejectionCode = '" + ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL + "' " +
                "AND tr.ledgerDispatchApproved IS FALSE " +
                "GROUP BY rv.transactionId " +
                ") ";

        String newVersion = "select count(newVersion) from ( " +
                "SELECT rv.transactionId newVersion " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id or tr.lastReconcilation IS NULL)  " +
                "AND rv.rejectionCode = '" + ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL + "' " +
                "AND tr.ledgerDispatchApproved IS TRUE " +
                "GROUP BY rv.transactionId " +

                ") ";

        String txOk = "select count(txOk) from ( " +
                "SELECT tx.id txOk " +
                "FROM accounting_reporting_core.TransactionEntity tx " +
                "WHERE (tx.reconcilation.finalStatus = 'OK')  " +
                "GROUP BY tx.id " +
                ") ";

        String txNok = "select count(txNok) from ( " +
                "SELECT rv.transactionId txNok " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id or tr.lastReconcilation IS NULL) AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved IS TRUE) OR (rv.rejectionCode != 'TX_NOT_IN_ERP')) " +
                "GROUP BY rv.transactionId, tr.id, rv.amountLcySum, rv.transactionEntryDate, rv.transactionInternalNumber, rv.transactionType " +
                ") ";

        String txNever = "select count(txNever) from ( " +
                "SELECT tx.id txNever " +
                "FROM accounting_reporting_core.TransactionEntity tx " +
                "WHERE (tx.lastReconcilation IS NULL)  " +
                "GROUP BY tx.id " +
                ") ";

        String finalQuery = "select (%s) missingInERP,(%s) inProcessing ,(%s) newInERP ,(%s) newVersionNotPublished ,(%s) newVersion ,(%s) txOk ,(%s) txNok ,(%s) txNever "
                .formatted(
                        missingInERP, inProcessing, newInERP, newVersionNotPublished, newVersion, txOk, txNok, txNever);

        Query reconciliationQuery = em.createQuery(finalQuery);

        return reconciliationQuery.getSingleResult();
    }

    private String reconciliationQuery(Set<ReconciliationRejectionCodeRequest> rejectionCodes, Optional<LocalDate> getDateFrom, Optional<LocalDate> getDateTo) {
        String jpql = "SELECT tr, rv " +
                "FROM accounting_reporting_core.reconcilation.ReconcilationEntity r " +
                "JOIN r.violations rv " +
                "LEFT JOIN accounting_reporting_core.TransactionEntity tr ON rv.transactionId = tr.id " +
                "WHERE (r.id = tr.lastReconcilation.id OR tr.lastReconcilation.id IS NULL) AND ((rv.rejectionCode = 'TX_NOT_IN_ERP' AND tr.ledgerDispatchApproved IS TRUE) OR (rv.rejectionCode != 'TX_NOT_IN_ERP')) ";

        String where = "";
        if (!rejectionCodes.isEmpty()) {
            List<ReconcilationRejectionCode> condition = new ArrayList<>(List.of());
            if (rejectionCodes.stream().anyMatch(reconciliationRejectionCodeRequest -> reconciliationRejectionCodeRequest.equals(ReconciliationRejectionCodeRequest.MISSING_IN_ERP))) {
                condition.add(ReconcilationRejectionCode.TX_NOT_IN_ERP);
            }

            if (rejectionCodes.stream().anyMatch(reconciliationRejectionCodeRequest -> reconciliationRejectionCodeRequest.equals(ReconciliationRejectionCodeRequest.IN_PROCESSING))) {
                condition.add(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
            }

            if (rejectionCodes.stream().anyMatch(reconciliationRejectionCodeRequest -> reconciliationRejectionCodeRequest.equals(ReconciliationRejectionCodeRequest.NEW_IN_ERP))) {
                condition.add(ReconcilationRejectionCode.TX_NOT_IN_LOB);
            }

            if (rejectionCodes.stream().anyMatch(reconciliationRejectionCodeRequest -> reconciliationRejectionCodeRequest.equals(ReconciliationRejectionCodeRequest.NEW_VERSION_NOT_PUBLISHED))) {
                condition.add(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
            }

            if (rejectionCodes.stream().anyMatch(reconciliationRejectionCodeRequest -> reconciliationRejectionCodeRequest.equals(ReconciliationRejectionCodeRequest.NEW_VERSION))) {
                condition.add(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
            }
            where += " AND rv.rejectionCode IN (%s) ".formatted(condition.stream().map(code -> "'" + code.name() + "'").collect(Collectors.joining(",")));
        }

        if (getDateFrom.isPresent()) {
            where += " AND tr.entryDate > :startDate ";
        }
        if (getDateTo.isPresent()) {
            where += " AND tr.entryDate < :endDate ";
        }
        where += "GROUP BY rv.transactionId, tr.id, rv.amountLcySum, rv.rejectionCode, rv.sourceDiff, rv.transactionEntryDate, rv.transactionInternalNumber, rv.transactionType ORDER BY rv.transactionEntryDate ";
        return jpql + where;
    }

}
