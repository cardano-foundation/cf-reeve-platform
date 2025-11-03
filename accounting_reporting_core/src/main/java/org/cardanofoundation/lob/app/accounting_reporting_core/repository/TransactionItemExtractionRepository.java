package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionItemAggregateView;

@Slf4j
@RequiredArgsConstructor
@Service
public class TransactionItemExtractionRepository {

    private final EntityManager em;
    private final TransactionItemRepository transactionItemRepository;

    public List<TransactionItemEntity> findByItemAccount(LocalDate dateFrom, LocalDate dateTo,
                                                         List<String> accountCode, List<String> costCenter,
                                                         List<String> project, List<String> accountType,
                                                         List<String> accountSubType) {

        StringBuilder jpql = new StringBuilder("""
                    SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti
                    INNER JOIN ti.transaction te
                    WHERE te.entryDate >= :dateFrom
                      AND te.entryDate <= :dateTo
                      AND ti.status = :status
                      AND te.ledgerDispatchStatus = :ledgerStatus
                """);

        if (accountCode != null && !accountCode.isEmpty()) {
            jpql.append("""
                    AND (
                        ti.accountDebit.code IN :accountCodes
                        OR ti.accountCredit.code IN :accountCodes
                    )
                    """);
        }

        if (accountSubType != null && !accountSubType.isEmpty()) {
            jpql.append("""
                    AND (
                        ti.accountDebit.code IN (
                            SELECT oc.Id.customerCode FROM ChartOfAccount oc
                            WHERE oc.subType.id IN :accountSubTypes
                        )
                        OR ti.accountCredit.code IN (
                            SELECT oc.Id.customerCode FROM ChartOfAccount oc
                            WHERE oc.subType.id IN :accountSubTypes
                        )
                    )
                    """);
        }

        if (accountType != null && !accountType.isEmpty()) {
            jpql.append("""
                    AND (
                        ti.accountDebit.code IN (
                            SELECT oc.Id.customerCode FROM ChartOfAccount oc
                            WHERE oc.subType.id IN (
                                SELECT st.id FROM ChartOfAccountSubType st
                                WHERE st.type.id IN :accountTypes
                            )
                        )
                        OR ti.accountCredit.code IN (
                            SELECT oc.Id.customerCode FROM ChartOfAccount oc
                            WHERE oc.subType.id IN (
                                SELECT st.id FROM ChartOfAccountSubType st
                                WHERE st.type.id IN :accountTypes
                            )
                        )
                    )
                    """);
        }

        if (costCenter != null && !costCenter.isEmpty()) {
            jpql.append("""
                    AND ti.costCenter.customerCode IN (SELECT cc.Id.customerCode from CostCenter cc
                    WHERE
                    cc.Id.customerCode in :costCenters OR
                    cc.parent.Id.customerCode in :costCenters OR
                    cc.Id.customerCode in (SELECT cc2.parent.Id.customerCode from CostCenter cc2 where cc2.Id.customerCode in :costCenters)
                    )
                    """);
        }

        if (project != null && !project.isEmpty()) {
            jpql.append(" AND ti.project.customerCode IN :projects");
        }
        TypedQuery<TransactionItemEntity> query = em.createQuery(jpql.toString(), TransactionItemEntity.class);
        query.setParameter("dateFrom", dateFrom);
        query.setParameter("dateTo", dateTo);
        query.setParameter("status", TxItemValidationStatus.OK);
        query.setParameter("ledgerStatus", LedgerDispatchStatus.FINALIZED);

        if (accountCode != null && !accountCode.isEmpty()) {
            query.setParameter("accountCodes", accountCode);
        }
        if (accountSubType != null && !accountSubType.isEmpty()) {
            query.setParameter("accountSubTypes", accountSubType);
        }
        if (accountType != null && !accountType.isEmpty()) {
            query.setParameter("accountTypes", accountType);
        }
        if (costCenter != null && !costCenter.isEmpty()) {
            query.setParameter("costCenters", costCenter);
        }
        if (project != null && !project.isEmpty()) {
            query.setParameter("projects", project);
        }

        return query.getResultList();
    }

    public long countItemsByAccountDateAggregated(String orgId, LocalDate dateFrom, LocalDate dateTo, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash) {
        String jpql = """
                SELECT COUNT(1) FROM accounting_reporting_core.TransactionItemEntity ti
                    JOIN ti.transaction te LEFT JOIN CostCenter cc ON ti.costCenter.customerCode = cc.id.customerCode
                """;

        String where = constructWhereClauseForExtraction(orgId, event, currency, minAmount, maxAmount, transactionHash);
        String grouping = "GROUP BY ti.accountEvent.code, ti.fxRate, ti.project.customerCode, cc.parentCustomerCode, ti.document.num";
        Query resultQuery = em.createQuery(jpql + where + grouping);

        resultQuery.setParameter("dateFrom", dateFrom);
        resultQuery.setParameter("dateTo", dateTo);

        return resultQuery.getResultList().size();
    }

    public List<TransactionItemEntity> findByItemAccountDateAggregated(String orgId, LocalDate dateFrom, LocalDate dateTo, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash, int page, int limit) {

        String jpql = """
                    SELECT NEW org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.TransactionItemAggregateView(MIN(ti.id), SUM(ti.amountLcy), SUM(ti.amountFcy))
                    FROM accounting_reporting_core.TransactionItemEntity ti
                    JOIN ti.transaction te LEFT JOIN CostCenter cc ON ti.costCenter.customerCode = cc.id.customerCode
                """;

        String where = constructWhereClauseForExtraction(orgId, event, currency, minAmount, maxAmount, transactionHash);

        String grouping = "GROUP BY ti.accountEvent.code, ti.fxRate, ti.project.customerCode, cc.parentCustomerCode, ti.document.num";
        Query resultQuery = em.createQuery(jpql + where + grouping);

        resultQuery.setParameter("dateFrom", dateFrom);
        resultQuery.setParameter("dateTo", dateTo);

        // adding pagination
        resultQuery.setFirstResult(page * limit);
        resultQuery.setMaxResults(limit);
        List<TransactionItemAggregateView> itemAggregateViews = resultQuery.getResultList();
        Map<String, TransactionItemAggregateView> collect =
                itemAggregateViews.stream().collect(Collectors.toMap(TransactionItemAggregateView::getTxId, item -> item));
        List<TransactionItemEntity> allById = transactionItemRepository.findAllById(collect.keySet());
        allById.stream().forEach(item -> {
            TransactionItemAggregateView aggregateView = collect.get(item.getId());
            if (aggregateView != null) {
                item.setAmountFcy(aggregateView.getAmountFcyAggregated());
                item.setAmountLcy(aggregateView.getAmountLcyAggregated());
            }
        });
        return allById;
    }

    private static String constructWhereClauseForExtraction(String orgId, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash) {
        minAmount = Optional.ofNullable(minAmount).orElse(Optional.empty());
        maxAmount = Optional.ofNullable(maxAmount).orElse(Optional.empty());


        String where = """
                WHERE te.entryDate >= :dateFrom AND te.entryDate <= :dateTo
                AND te.organisation.id = '%s'
                AND ti.status = '%s'
                """.formatted(orgId, TxItemValidationStatus.OK);

        if (!event.isEmpty()) {
            where += """
            AND (ti.accountEvent.code in (%s) )
            """.formatted(event.stream().map(code -> "'" + code + "'").collect(Collectors.joining(",")));
        }

        if (!currency.isEmpty()) {
            where += """
            AND (ti.document.currency.customerCode in (%s) )
            """.formatted(currency.stream().map(code -> "'" + code + "'").collect(Collectors.joining(",")));
        }

        if (minAmount.isPresent()) {
            where += """
            AND ABS(ti.amountFcy) >= %s
            """.formatted(minAmount.get());
        }

        if (maxAmount.isPresent()) {
            where += """
            AND ABS(ti.amountFcy) <= %s
            """.formatted(maxAmount.get());
        }

        if (!transactionHash.isEmpty() && 0 < transactionHash.stream().count()) {
            where += """
            AND (te.ledgerDispatchReceipt.primaryBlockchainHash in (%s))
            """.formatted(transactionHash.stream().map(code -> "'" + code + "'").collect(Collectors.joining(",")));
        }

        where += """
        AND te.ledgerDispatchStatus = '%s'
        """.formatted(LedgerDispatchStatus.FINALIZED);
        return where;
    }


}
