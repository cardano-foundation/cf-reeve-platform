package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;

import org.mockito.Mockito;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.BatchSearchRequest;

class CustomTransactionBatchRepositoryImplTest {

    /*
    @Test
    void findByFilterTest() {
        CriteriaBuilder builder = Mockito.mock(CriteriaBuilder.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        CriteriaQuery<TransactionBatchEntity> criteriaQuery = Mockito.mock(String.valueOf(TransactionBatchEntity.class));
        Root<TransactionBatchEntity> rootEntry = Mockito.mock(String.valueOf(TransactionBatchEntity.class));
        TypedQuery<TransactionBatchEntity> theQuery = Mockito.mock(String.valueOf(TransactionBatchEntity.class));
        Path<Object> filteringParameters = Mockito.mock(Path.class);
        Path<Object> organisationId = Mockito.mock(Path.class);
        Join transactionEntityJoin = Mockito.mock(Join.class);
        CriteriaBuilder.In inResult = Mockito.mock(CriteriaBuilder.In.class);
        Path<Object> batchStatistics = Mockito.mock(Path.class);

        BatchSearchRequest body = new BatchSearchRequest();
        body.setLimit(10);
        body.setPage(1);
        body.setOrganisationId("TestOrgId");
        body.setBatchStatistics(Set.of(LedgerDispatchStatusView.APPROVE, LedgerDispatchStatusView.PENDING, LedgerDispatchStatusView.INVALID, LedgerDispatchStatusView.PUBLISH, LedgerDispatchStatusView.PUBLISHED));
        body.setTxStatus(Set.of(TransactionStatus.OK));
        body.setTransactionTypes(Set.of(TransactionType.CardCharge, TransactionType.FxRevaluation));
        body.setFrom(LocalDate.now());
        body.setTo(LocalDate.now());

        Mockito.when(em.getCriteriaBuilder()).thenReturn(builder);
        Mockito.when(builder.createQuery(TransactionBatchEntity.class)).thenReturn(criteriaQuery);
        Mockito.when(criteriaQuery.from(TransactionBatchEntity.class)).thenReturn(rootEntry);
        Mockito.when(rootEntry.get("filteringParameters")).thenReturn(filteringParameters);
        Mockito.when(filteringParameters.get("organizationId")).thenReturn(organisationId);
        Mockito.when(rootEntry.join("transactions", JoinType.INNER)).thenReturn(transactionEntityJoin);
        Mockito.when(builder.in(transactionEntityJoin.get("status"))).thenReturn(inResult);
        Mockito.when(rootEntry.get("batchStatistics")).thenReturn(batchStatistics);

        Mockito.when(em.createQuery(criteriaQuery)).thenReturn(theQuery);
        CustomTransactionBatchRepositoryImpl customTransactionBatchRepository = new CustomTransactionBatchRepositoryImpl(em);

        List<TransactionBatchEntity> result = customTransactionBatchRepository.findByFilter(body);


        Mockito.verify(theQuery,Mockito.times(1)).setFirstResult(10);

    }
*/
    @Test
    void findByFilterAllTest() {
        CriteriaBuilder builder = Mockito.mock(CriteriaBuilder.class);
        EntityManager em = Mockito.mock(EntityManager.class);
        CriteriaQuery<TransactionBatchEntity> criteriaQuery = Mockito.mock(String.valueOf(TransactionBatchEntity.class));
        Root<TransactionBatchEntity> rootEntry = Mockito.mock(String.valueOf(TransactionBatchEntity.class));
        TypedQuery<TransactionBatchEntity> theQuery = Mockito.mock(String.valueOf(TransactionBatchEntity.class));
        Path<Object> filteringParameters = Mockito.mock(Path.class);
        Path<Object> organisationId = Mockito.mock(Path.class);
        Join transactionEntityJoin = Mockito.mock(Join.class);
        CriteriaBuilder.In inResult = Mockito.mock(CriteriaBuilder.In.class);

        BatchSearchRequest body = new BatchSearchRequest();
        body.setLimit(10);
        body.setPage(1);
        body.setOrganisationId("TestOrgId");

        Mockito.when(em.getCriteriaBuilder()).thenReturn(builder);
        Mockito.when(builder.createQuery(TransactionBatchEntity.class)).thenReturn(criteriaQuery);
        Mockito.when(criteriaQuery.from(TransactionBatchEntity.class)).thenReturn(rootEntry);
        Mockito.when(rootEntry.get("filteringParameters")).thenReturn(filteringParameters);
        Mockito.when(filteringParameters.get("organizationId")).thenReturn(organisationId);
        Mockito.when(rootEntry.join("transactions", JoinType.INNER)).thenReturn(transactionEntityJoin);
        Mockito.when(builder.in(transactionEntityJoin.get("status"))).thenReturn(inResult);

        Mockito.when(em.createQuery(criteriaQuery)).thenReturn(theQuery);
        CustomTransactionBatchRepositoryImpl customTransactionBatchRepository = new CustomTransactionBatchRepositoryImpl(em);

        List<TransactionBatchEntity> result = customTransactionBatchRepository.findByFilter(body);

        Mockito.verify(transactionEntityJoin,Mockito.times(0)).get("ledgerDispatchStatus");
        Mockito.verify(transactionEntityJoin,Mockito.times(0)).get("automatedValidationStatus");
        Mockito.verify(theQuery,Mockito.times(1)).setFirstResult(10);

    }
}
