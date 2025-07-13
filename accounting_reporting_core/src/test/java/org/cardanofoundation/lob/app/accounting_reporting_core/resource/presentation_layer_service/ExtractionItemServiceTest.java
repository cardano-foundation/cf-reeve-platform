package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemExtractionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@ExtendWith(MockitoExtension.class)
class ExtractionItemServiceTest {

    @Mock
    private TransactionItemExtractionRepository transactionItemExtractionRepository;

    @Mock
    private OrganisationPublicApi organisationPublicApi;

    @Test
    void findTransactionItemsTest() {
        val document = Document.builder()
                .currency(Currency.builder()
                        .customerCode("EUR")
                        .build())
                .build();
        val tx = new TransactionEntity();
        tx.setId("TxId1");
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("orgId1").build());
        tx.setTransactionType(TransactionType.FxRevaluation);
        tx.setLedgerDispatchReceipt(new LedgerDispatchReceipt());


        val item1 = new TransactionItemEntity();
        item1.setId("item1");
        item1.setDocument(Optional.of(document));
        item1.setAmountFcy(BigDecimal.valueOf(1));
        item1.setAmountLcy(BigDecimal.valueOf(1));
        item1.setCostCenter(Optional.ofNullable(CostCenter.builder().customerCode("10201").externalCustomerCode("10201").build()));
        tx.setItems(Set.of(item1));

        item1.setTransaction(tx);

        Mockito.when(transactionItemExtractionRepository.findByItemAccount(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of(item1));
        Mockito.when(organisationPublicApi.findProject(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        Mockito.when(organisationPublicApi.findCostCenter(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        ExtractionItemService extractionItemService = new ExtractionItemService(transactionItemExtractionRepository, organisationPublicApi);

        ExtractionTransactionView result = extractionItemService.findTransactionItems(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        assertInstanceOf(ExtractionTransactionView.class, result);
        assertEquals(1L, result.getTotal());
        verifyNoMoreInteractions(transactionItemExtractionRepository);

    }

    @Test
    void findByItemAccountDateTest() {
        val document = Document.builder()
                .currency(Currency.builder()
                        .customerCode("EUR")
                        .build())
                .build();
        val tx = new TransactionEntity();
        tx.setId("TxId1");
        tx.setTransactionInternalNumber("1");
        tx.setOrganisation(Organisation.builder().id("orgId1").build());
        tx.setTransactionType(TransactionType.FxRevaluation);
        tx.setLedgerDispatchReceipt(new LedgerDispatchReceipt());


        val item1 = new TransactionItemEntity();
        item1.setId("item1");
        item1.setDocument(Optional.of(document));
        item1.setAmountFcy(BigDecimal.valueOf(1));
        item1.setAmountLcy(BigDecimal.valueOf(1));
        item1.setCostCenter(Optional.ofNullable(CostCenter.builder().customerCode("10201").externalCustomerCode("10201").build()));
        tx.setItems(Set.of(item1));

        item1.setTransaction(tx);

        Mockito.when(transactionItemExtractionRepository.findByItemAccountDateAggregated(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of(item1));
        Mockito.when(organisationPublicApi.findProject(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        Mockito.when(organisationPublicApi.findCostCenter(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        ExtractionItemService extractionItemService = new ExtractionItemService(transactionItemExtractionRepository, organisationPublicApi);

        ExtractionTransactionView result = extractionItemService.findTransactionItemsPublic(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), 0, 10);
        assertInstanceOf(ExtractionTransactionView.class, result);
        assertEquals(1L, result.getTotal());
        assertEquals("item1", result.getTransactions().getFirst().getId());
        assertEquals("TxId1", result.getTransactions().getFirst().getTransactionID());
        verifyNoMoreInteractions(transactionItemExtractionRepository);

    }
}
