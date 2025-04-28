package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.VendorBill;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus.VALIDATED;
import static org.mockito.Mockito.*;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

import lombok.val;

import org.springframework.data.domain.PageRequest;

import org.mockito.Mockito;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;

class CustomBlockchainReaderAccountingCoreTransactionRepositoryImplTestView {

    @Test
    void findAllByStatus() {
        val builder = mock(CriteriaBuilder.class);
        CriteriaQuery<TransactionEntity> criteriaQuery = mock(String.valueOf(TransactionEntity.class));
        EntityManager em = mock(EntityManager.class);

        TransactionEntity transactionEntity = mock(TransactionEntity.class);
        TransactionEntity transactionEntity2 = mock(TransactionEntity.class);
        transactionEntity.setId("nothing");
        transactionEntity2.setId("nothing2");
        List<TransactionEntity> transactions = List.of(transactionEntity, transactionEntity2);
        Root<TransactionEntity> rootEntry = mock(String.valueOf(TransactionEntity.class));

        TypedQuery<TransactionEntity> transactionEntityTypedQuery = mock(String.valueOf(TransactionEntity.class));

        Mockito.when(em.getCriteriaBuilder()).thenReturn(builder);
        Mockito.when(builder.createQuery(TransactionEntity.class)).thenReturn(criteriaQuery);
        Mockito.when(criteriaQuery.from(TransactionEntity.class)).thenReturn(rootEntry);
        Mockito.when(transactionEntityTypedQuery.getResultList()).thenReturn(transactions);
        Path<Object> validationStatus = mock(Path.class);
        Path<Object> organisation = mock(Path.class);
        Path<Object> organisationId = mock(Path.class);

        val inResult = mock(CriteriaBuilder.In.class);

        Mockito.when(rootEntry.get("automatedValidationStatus")).thenReturn(validationStatus);
        Mockito.when(rootEntry.get("organisation")).thenReturn(organisation);
        Mockito.when(organisation.get("id")).thenReturn(organisationId);
        Mockito.when(builder.in(rootEntry.get("automatedValidationStatus"))).thenReturn(inResult);

        Mockito.when(em.createQuery(criteriaQuery)).thenReturn(transactionEntityTypedQuery);
        CustomTransactionRepositoryImpl customTransactionRepository = new CustomTransactionRepositoryImpl(em);

        List<TransactionEntity> elresult = customTransactionRepository.findAllByStatus("OrgId",
                List.of(TxValidationStatus.valueOf(String.valueOf(VALIDATED))),
                List.of(TransactionType.valueOf(String.valueOf(VendorBill))), PageRequest.of(0, 100));

        //Mockito.verify(builder,Mockito.times(4)).isTrue(builder.literal(true));
        verify(builder, times(1)).in(validationStatus);
        verify(builder, times(1)).equal(organisationId, "OrgId");
        verify(inResult, times(1)).value(List.of(TxValidationStatus.valueOf(String.valueOf(VALIDATED))));
        verify(rootEntry, times(1)).get("organisation");
        verify(organisation, times(1)).get("id");
        Assertions.assertEquals(List.of(transactionEntity, transactionEntity2), elresult);
    }

}
