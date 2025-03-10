package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

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

    public List<TransactionItemEntity> findByItemAccount(LocalDate dateFrom, LocalDate dateTo, List<String> accountCode, List<String> costCenter, List<String> project, List<String> accountType, List<String> accountSubType) {
        String jpql = STR."""
                SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti INNER JOIN ti.transaction te
                """;
        String where = STR."""
                WHERE te.entryDate >= :dateFrom AND te.entryDate <= :dateTo
                AND ti.status = '\{TxItemValidationStatus.OK}'
                """;

        if (null != accountCode && 0 < accountCode.stream().count()) {
            where += STR."""
            AND (ti.accountDebit.code in (\{accountCode.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))}) or ti.accountCredit.code in (\{accountCode.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))}))
            """;
        }
        if (null != accountSubType && 0 < accountSubType.stream().count()) {
            where += STR."""
            AND (
            ti.accountDebit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id  in (\{accountSubType.stream().map(code -> "" + code + "").collect(Collectors.joining(","))})) or
            ti.accountCredit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id in (\{accountSubType.stream().map(code -> "" + code + "").collect(Collectors.joining(","))}))
            )
            """;
        }

        if (null != accountType && 0 < accountType.stream().count()) {
            where += STR."""
            AND (
            ti.accountDebit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id  in (select id from OrganisationChartOfAccountSubType where type.id in (\{accountType.stream().map(code -> "" + code + "").collect(Collectors.joining(","))}))) or
            ti.accountCredit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id in (select id from OrganisationChartOfAccountSubType where type.id in (\{accountType.stream().map(code -> "" + code + "").collect(Collectors.joining(","))})))
            )
            """;
        }

        if (null != costCenter && 0 < costCenter.stream().count()) {
            where += STR."""
            AND ti.costCenter.customerCode in (\{costCenter.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))})
            """;
        }

        if (null != project && 0 < project.stream().count()) {
            where += STR."""
            AND ti.project.customerCode in (\{project.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))})
            """;
        }

        where += STR."""
        AND te.ledgerDispatchStatus = '\{LedgerDispatchStatus.FINALIZED}'
        """;

        Query resultQuery = em.createQuery(jpql + where);

        resultQuery.setParameter("dateFrom", dateFrom);
        resultQuery.setParameter("dateTo", dateTo);

        return resultQuery.getResultList();
    }


    public List<TransactionItemEntity> findByItemAccountDate(String orgId, LocalDate dateFrom, LocalDate dateTo, Set<String> event, Set<String> currency, Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount, Set<String> transactionHash) {

        minAmount = Optional.ofNullable(minAmount).orElse(Optional.empty());
        maxAmount = Optional.ofNullable(maxAmount).orElse(Optional.empty());

        String jpql = STR."""
                SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti INNER JOIN ti.transaction te
                """;
        String where = STR."""
                WHERE te.entryDate >= :dateFrom AND te.entryDate <= :dateTo
                AND te.organisation.id = '\{orgId}'
                AND ti.status = '\{TxItemValidationStatus.OK}'
                """;

        if (!event.isEmpty()) {
            where += STR."""
            AND (ti.accountEvent.code in (\{event.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))}) )
            """;
        }

        if (!currency.isEmpty()) {
            where += STR."""
            AND (ti.document.currency.customerCode in (\{currency.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))}) )
            """;
        }

        if (minAmount.isPresent()) {
            where += STR."""
            AND ABS(ti.amountFcy) >= \{minAmount.get()}
            """;
        }

        if (maxAmount.isPresent()) {
            where += STR."""
            AND ABS(ti.amountFcy) <= \{maxAmount.get()}
            """;
        }

        if (!transactionHash.isEmpty() && 0 < transactionHash.stream().count()) {
            where += STR."""
            AND (te.ledgerDispatchReceipt.primaryBlockchainHash in (\{transactionHash.stream().map(code -> "'" + code + "'").collect(Collectors.joining(","))}))
            """;
        }

        where += STR."""
        AND te.ledgerDispatchStatus = '\{LedgerDispatchStatus.FINALIZED}'
        """;

        Query resultQuery = em.createQuery(jpql + where);

        resultQuery.setParameter("dateFrom", dateFrom);
        resultQuery.setParameter("dateTo", dateTo);

        return resultQuery.getResultList();
    }


}
