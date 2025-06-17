package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

@Slf4j
@RequiredArgsConstructor
@Service
public class TransactionItemExtractionRepository {

    private final EntityManager em;

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
                            SELECT oc.Id.customerCode FROM OrganisationChartOfAccount oc
                            WHERE oc.subType.id IN :accountSubTypes
                        )
                        OR ti.accountCredit.code IN (
                            SELECT oc.Id.customerCode FROM OrganisationChartOfAccount oc
                            WHERE oc.subType.id IN :accountSubTypes
                        )
                    )
                    """);
        }

        if (accountType != null && !accountType.isEmpty()) {
            jpql.append("""
                    AND (
                        ti.accountDebit.code IN (
                            SELECT oc.Id.customerCode FROM OrganisationChartOfAccount oc
                            WHERE oc.subType.id IN (
                                SELECT st.id FROM OrganisationChartOfAccountSubType st
                                WHERE st.type.id IN :accountTypes
                            )
                        )
                        OR ti.accountCredit.code IN (
                            SELECT oc.Id.customerCode FROM OrganisationChartOfAccount oc
                            WHERE oc.subType.id IN (
                                SELECT st.id FROM OrganisationChartOfAccountSubType st
                                WHERE st.type.id IN :accountTypes
                            )
                        )
                    )
                    """);
        }

        if (costCenter != null && !costCenter.isEmpty()) {
            jpql.append(" AND ti.costCenter.customerCode IN :costCenters");
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


    public List<TransactionItemEntity> findByItemAccountDate(String orgId, LocalDate dateFrom, LocalDate dateTo, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash) {

        minAmount = Optional.ofNullable(minAmount).orElse(Optional.empty());
        maxAmount = Optional.ofNullable(maxAmount).orElse(Optional.empty());

        String jpql = """
                SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti INNER JOIN ti.transaction te
                """;
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

        Query resultQuery = em.createQuery(jpql + where);

        resultQuery.setParameter("dateFrom", dateFrom);
        resultQuery.setParameter("dateTo", dateTo);

        return resultQuery.getResultList();
    }


}
