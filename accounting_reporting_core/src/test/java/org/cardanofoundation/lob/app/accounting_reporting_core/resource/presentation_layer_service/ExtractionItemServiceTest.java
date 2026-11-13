package org.cardanofoundation.lob.app.accounting_reporting_core.resource.presentation_layer_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.LedgerDispatchReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ExtractionTransactionsRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ExtractionTransactionView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@ExtendWith(MockitoExtension.class)
class ExtractionItemServiceTest {

    @Mock
    private TransactionItemRepository transactionItemRepository;

    @Mock
    private OrganisationPublicApi organisationPublicApi;

    @InjectMocks
    private ExtractionItemService extractionItemService;

    @Test
    void findTransactionItemsTest() {
        val document = Document.builder()
                .currency(Currency.builder()
                        .customerCode("EUR")
                        .build())
                .build();
        val tx = new TransactionEntity();
        tx.setId("TxId1");
        tx.setInternalTransactionNumber("1");
        tx.setOrganisation(Organisation.builder().id("orgId1").build());
        tx.setTransactionType(TransactionType.FxRevaluation);
        tx.setLedgerDispatchReceipt(new LedgerDispatchReceipt());


        val item1 = new TransactionItemEntity();
        item1.setId("item1");
        item1.setDocument(Optional.of(document));
        item1.setAmountFcy(BigDecimal.valueOf(1));
        item1.setAmountLcy(BigDecimal.valueOf(1));
        item1.setCostCenter(Optional.ofNullable(CostCenter.builder().customerCode("10201").build()));
        tx.setItems(Set.of(item1));

        item1.setTransaction(tx);

        val request = new ExtractionTransactionsRequest();
        request.setOrganisationId("orgId1");
        val pageable = PageRequest.of(0, 10);

        Mockito.when(transactionItemRepository.searchItems(Mockito.eq("orgId1"), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(item1), pageable, 1));
        Mockito.when(organisationPublicApi.findProject(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        Mockito.when(organisationPublicApi.findCostCenter(Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        ExtractionTransactionView result = extractionItemService.findTransactionItems(request, pageable);
        assertInstanceOf(ExtractionTransactionView.class, result);
        assertEquals(1L, result.getTotal());
        verifyNoMoreInteractions(transactionItemRepository);

    }
}
