package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;

@ExtendWith(MockitoExtension.class)
class TransactionItemExtractionRepositoryTest {

    @Mock
    private EntityManager em;

    @Test
    void findByItemAccountOnlyDates() {
        TypedQuery queryResult = Mockito.mock(TypedQuery.class);
        TransactionItemExtractionRepository transactionItemExtractionRepository = new TransactionItemExtractionRepository(em);

        Mockito.when(em.createQuery(anyString(), eq(TransactionItemEntity.class))).thenReturn(queryResult);
        transactionItemExtractionRepository.findByItemAccount(
                LocalDate.of(2023, Month.JANUARY, 1),
                LocalDate.of(2023, Month.JANUARY, 31),
                List.of("AccountCode"),
                List.of("CostCenterCode"),
                List.of("ProjectCode"),
                List.of("accountType"),
                List.of("accountSubType")
        );
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(em).createQuery(queryCaptor.capture(), eq(TransactionItemEntity.class));
        String expectedQuery = """
            SELECT ti FROM accounting_reporting_core.TransactionItemEntity ti
            INNER JOIN ti.transaction te
            WHERE te.entryDate >= :dateFrom AND te.entryDate <= :dateTo
            AND ti.status = :status AND te.ledgerDispatchStatus = :ledgerStatus
            AND (
                ti.accountDebit.code IN :accountCodes OR ti.accountCredit.code IN :accountCodes
            )
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
            AND ti.costCenter.customerCode IN :costCenters AND ti.project.customerCode IN :projects
            """;

        assertEquals(normalize(expectedQuery), normalize(queryCaptor.getValue()));
    }

    String normalize(String input) {
        return input
                .replaceAll("\\s+", " ") // replaces all kinds of whitespace (tabs, newlines) with single space
                .trim();
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
                Set.of("TheHast2", "TheHast1"),
                0,
                10
        );
        Mockito.verify(em, Mockito.times(1)).createQuery(anyString());
    }
}
