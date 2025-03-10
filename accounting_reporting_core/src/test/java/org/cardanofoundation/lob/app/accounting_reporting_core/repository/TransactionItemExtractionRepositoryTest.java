package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class TransactionItemExtractionRepositoryTest {

    @Mock
    private EntityManager em;

    @Test
    void findByItemAccountOnlyDates() {
        String query = """
                SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti INNER JOIN ti.transaction te
                WHERE te.entryDate >= :dateFrom AND te.entryDate <= :dateTo
                AND ti.status = 'OK'
                AND (ti.accountDebit.code in ('AccountCode') or ti.accountCredit.code in ('AccountCode'))
                AND (
                ti.accountDebit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id  in (accountSubType)) or
                ti.accountCredit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id in (accountSubType))
                )
                AND (
                ti.accountDebit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id  in (select id from OrganisationChartOfAccountSubType where type.id in (accountType))) or
                ti.accountCredit.code in (select Id.customerCode from OrganisationChartOfAccount where subType.id in (select id from OrganisationChartOfAccountSubType where type.id in (accountType)))
                )
                AND ti.costCenter.customerCode in ('CostCenterCode')
                AND ti.project.customerCode in ('ProjectCode')
                AND te.ledgerDispatchStatus = 'FINALIZED'
                """;
        jakarta.persistence.Query queryResult = Mockito.mock(Query.class);
        TransactionItemExtractionRepository transactionItemExtractionRepository = new TransactionItemExtractionRepository(em);

        Mockito.when(em.createQuery(anyString())).thenReturn(queryResult);
        transactionItemExtractionRepository.findByItemAccount(
                LocalDate.of(2023, Month.JANUARY, 1),
                LocalDate.of(2023, Month.JANUARY, 31),
                List.of("AccountCode"),
                List.of("CostCenterCode"),
                List.of("ProjectCode"),
                List.of("accountType"),
                List.of("accountSubType")
        );
        Mockito.verify(em, Mockito.times(1)).createQuery(query);
    }

    @Test
    void findByItemAccountDate() {
        jakarta.persistence.Query queryResult = Mockito.mock(Query.class);
        TransactionItemExtractionRepository transactionItemExtractionRepository = new TransactionItemExtractionRepository(em);

        Mockito.when(em.createQuery(anyString())).thenReturn(queryResult);
        transactionItemExtractionRepository.findByItemAccountDate(
                "OrgId",
                LocalDate.of(2023, Month.JANUARY, 1),
                LocalDate.of(2023, Month.JANUARY, 31),
                Set.of("EventCode2", "EventCode1"),
                Set.of("Currency2", "Currency1"),
                Optional.of(BigDecimal.valueOf(100)),
                Optional.of(BigDecimal.valueOf(1000)),
                Set.of("TheHast2", "TheHast1")
        );
        Mockito.verify(em, Mockito.times(1)).createQuery(anyString());
    }
}
