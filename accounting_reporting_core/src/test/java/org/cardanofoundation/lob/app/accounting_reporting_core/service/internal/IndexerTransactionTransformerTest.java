package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Project;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Vat;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer.TransformedTransactionItem;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@ExtendWith(MockitoExtension.class)
class IndexerTransactionTransformerTest {

    private static final String ORG_ID = "test-org";

    @Mock
    private OrganisationPublicApi organisationPublicApi;

    private IndexerTransactionTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new IndexerTransactionTransformer(organisationPublicApi);
    }

    @Test
    void transformForIndexerComparison_shouldTransformBasicTransaction() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        assertThat(result.getId()).isEqualTo("tx-1");
        assertThat(result.getInternalNumber()).isEqualTo("INT-001");
        assertThat(result.getTransactionType()).isEqualTo("VendorPayment");
        assertThat(result.getEntryDate()).isEqualTo("2024-01-15");
        assertThat(result.getBatchId()).isEqualTo("batch-1");
        assertThat(result.getOrganisationId()).isEqualTo(ORG_ID);
        assertThat(result.getItems()).hasSize(1);
    }

    @Test
    void transformForIndexerComparison_shouldUseLcyForFxRevaluationTransactions() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.FxRevaluation, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1100), BigDecimal.ONE);
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        assertThat(result.getItems()).hasSize(1);
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        // For FxRevaluation, amountFcy should be amountLcy (1100)
        assertThat(transformedItem.getAmountFcy()).isEqualByComparingTo(BigDecimal.valueOf(1100));
    }

    @Test
    void transformForIndexerComparison_shouldUseFcyForNonFxRevaluationTransactions() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1100), BigDecimal.ONE);
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        // For non-FxRevaluation, amountFcy should remain as amountFcy (1000)
        assertThat(transformedItem.getAmountFcy()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void transformForIndexerComparison_shouldResolveParentCostCenter() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        item.setCostCenter(Optional.of(CostCenter.builder().customerCode("CHILD-9001").build()));
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // Mock parent cost center lookup
        org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter parentCostCenter =
                org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.builder()
                        .id(new org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.Id(ORG_ID, "PARENT-9000"))
                        .name("Parent Cost Center")
                        .build();

        org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter childCostCenter =
                org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.builder()
                        .id(new org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.Id(ORG_ID, "CHILD-9001"))
                        .name("Child Cost Center")
                        .parent(parentCostCenter)
                        .build();

        when(organisationPublicApi.findCostCenter(ORG_ID, "CHILD-9001"))
                .thenReturn(Optional.of(childCostCenter));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getCostCenterCustomerCode()).isEqualTo("PARENT-9000");
    }

    @Test
    void transformForIndexerComparison_shouldUseOwnCodeWhenNoParentCostCenter() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        item.setCostCenter(Optional.of(CostCenter.builder().customerCode("9000").build()));
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // Mock cost center without parent (parent is null)
        org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter costCenter =
                org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.builder()
                        .id(new org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.Id(ORG_ID, "9000"))
                        .name("Cost Center")
                        .parent(null)
                        .build();

        when(organisationPublicApi.findCostCenter(ORG_ID, "9000"))
                .thenReturn(Optional.of(costCenter));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getCostCenterCustomerCode()).isEqualTo("9000");
    }

    @Test
    void transformForIndexerComparison_shouldAggregateItemsWithSameKey() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");

        TransactionItemEntity item1 = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        item1.setCostCenter(Optional.of(CostCenter.builder().customerCode("9000").build()));
        item1.setProject(Optional.of(Project.builder().customerCode("PRJ001").build()));
        item1.setTransaction(tx);

        TransactionItemEntity item2 = createItem("item-2", BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.ONE);
        item2.setCostCenter(Optional.of(CostCenter.builder().customerCode("9000").build()));
        item2.setProject(Optional.of(Project.builder().customerCode("PRJ001").build()));
        item2.setTransaction(tx);

        tx.setAllItems(Set.of(item1, item2));

        // Mock cost center lookup for both (same code, no parent)
        org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter costCenter =
                org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.builder()
                        .id(new org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.Id(ORG_ID, "9000"))
                        .name("Cost Center")
                        .parent(null)
                        .build();

        when(organisationPublicApi.findCostCenter(ORG_ID, "9000"))
                .thenReturn(Optional.of(costCenter));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        // Items with same key should be aggregated
        assertThat(result.getItems()).hasSize(1);
        TransformedTransactionItem aggregatedItem = result.getItems().get(0);
        assertThat(aggregatedItem.getAmountFcy()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    }

    @Test
    void transformForIndexerComparison_shouldNotAggregateItemsWithDifferentKeys() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");

        TransactionItemEntity item1 = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        item1.setCostCenter(Optional.of(CostCenter.builder().customerCode("9000").build()));
        item1.setProject(Optional.of(Project.builder().customerCode("PRJ001").build()));
        item1.setTransaction(tx);

        TransactionItemEntity item2 = createItem("item-2", BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.ONE);
        item2.setCostCenter(Optional.of(CostCenter.builder().customerCode("9001").build())); // Different cost center
        item2.setProject(Optional.of(Project.builder().customerCode("PRJ001").build()));
        item2.setTransaction(tx);

        tx.setAllItems(Set.of(item1, item2));

        // Mock cost center lookups (no parents)
        org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter costCenter9000 =
                org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.builder()
                        .id(new org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.Id(ORG_ID, "9000"))
                        .name("Cost Center 9000")
                        .parent(null)
                        .build();

        org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter costCenter9001 =
                org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.builder()
                        .id(new org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter.Id(ORG_ID, "9001"))
                        .name("Cost Center 9001")
                        .parent(null)
                        .build();

        when(organisationPublicApi.findCostCenter(ORG_ID, "9000"))
                .thenReturn(Optional.of(costCenter9000));
        when(organisationPublicApi.findCostCenter(ORG_ID, "9001"))
                .thenReturn(Optional.of(costCenter9001));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        // Items with different keys should not be aggregated
        assertThat(result.getItems()).hasSize(2);
    }

    @Test
    void transformForIndexerComparison_shouldPreserveAllItemFields() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), new BigDecimal("1.25"));
        item.setCostCenter(Optional.of(CostCenter.builder().customerCode("9000").build()));
        item.setProject(Optional.of(Project.builder().customerCode("PRJ001").build()));
        item.setAccountEvent(Optional.of(AccountEvent.builder().code("EVENT01").build()));

        Document doc = Document.builder()
                .num("DOC-001")
                .currency(Currency.builder().customerCode("CHF").build())
                .vat(Vat.builder().customerCode("VAT-19").build())
                .build();
        item.setDocument(Optional.of(doc));
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        when(organisationPublicApi.findCostCenter(ORG_ID, "9000"))
                .thenReturn(Optional.empty());

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getId()).isEqualTo("item-1");
        assertThat(transformedItem.getAmountFcy()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(transformedItem.getFxRate()).isEqualByComparingTo(new BigDecimal("1.25"));
        assertThat(transformedItem.getDocumentNumber()).isEqualTo("DOC-001");
        assertThat(transformedItem.getCurrencyCustomerCode()).isEqualTo("CHF");
        assertThat(transformedItem.getCostCenterCustomerCode()).isEqualTo("9000");
        assertThat(transformedItem.getProjectCustomerCode()).isEqualTo("PRJ001");
        assertThat(transformedItem.getEventCode()).isEqualTo("EVENT01");
        assertThat(transformedItem.getVatCustomerCode()).isEqualTo("VAT-19");
    }

    private TransactionEntity createTransaction(String id, String internalNumber, TransactionType type, String date, String batchId) {
        TransactionEntity tx = TransactionEntity.builder()
                .internalTransactionNumber(internalNumber)
                .transactionType(type)
                .entryDate(LocalDate.parse(date))
                .batchId(batchId)
                .accountingPeriod(YearMonth.of(2024, 1))
                .organisation(Organisation.builder().id(ORG_ID).build())
                .build();
        tx.setId(id);
        return tx;
    }

    @Test
    void transformForIndexerComparison_shouldHandleNullCostCenterCode() {
        // Given - cost center exists but has null customerCode
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        // Set cost center with null customerCode
        item.setCostCenter(Optional.of(CostCenter.builder().customerCode(null).build()));
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then - cost center should be null when customerCode is null
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getCostCenterCustomerCode()).isNull();
    }

    @Test
    void transformForIndexerComparison_shouldHandleDocumentWithCounterparty() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);

        org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Counterparty counterparty =
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Counterparty.builder()
                        .customerCode("VENDOR-001")
                        .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                        .build();

        Document doc = Document.builder()
                .num("DOC-001")
                .currency(Currency.builder().customerCode("CHF").build())
                .counterparty(counterparty)
                .build();
        item.setDocument(Optional.of(doc));
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then - counterparty should be preserved in transformation
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getDocumentNumber()).isEqualTo("DOC-001");
    }

    @Test
    void transformForIndexerComparison_shouldHandleItemWithoutAccountEvent() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        // Account event is already empty in createItem()
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then - event code should be null
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getEventCode()).isNull();
    }

    @Test
    void transformForIndexerComparison_shouldHandleItemWithoutProject() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        // Project is already empty in createItem()
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then - project code should be null
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getProjectCustomerCode()).isNull();
    }

    @Test
    void transformForIndexerComparison_shouldHandleItemWithoutDocument() {
        // Given
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        // Document is already empty in createItem()
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then - document fields should be null
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getDocumentNumber()).isNull();
        assertThat(transformedItem.getCurrencyCustomerCode()).isNull();
        assertThat(transformedItem.getVatCustomerCode()).isNull();
    }

    @Test
    void transformForIndexerComparison_shouldUseCostCenterCodeWhenNotFoundInOrg() {
        // Given - cost center not found in organisation API should use the code from the item
        TransactionEntity tx = createTransaction("tx-1", "INT-001", TransactionType.VendorPayment, "2024-01-15", "batch-1");
        TransactionItemEntity item = createItem("item-1", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), BigDecimal.ONE);
        item.setCostCenter(Optional.of(CostCenter.builder().customerCode("UNKNOWN-CC").build()));
        item.setTransaction(tx);
        tx.setAllItems(Set.of(item));

        // Mock cost center not found
        when(organisationPublicApi.findCostCenter(ORG_ID, "UNKNOWN-CC"))
                .thenReturn(Optional.empty());

        // When
        TransformedTransaction result = transformer.transformForIndexerComparison(tx);

        // Then - should use the original code since not found in org
        TransformedTransactionItem transformedItem = result.getItems().get(0);
        assertThat(transformedItem.getCostCenterCustomerCode()).isEqualTo("UNKNOWN-CC");
    }

    private TransactionItemEntity createItem(String id, BigDecimal amountFcy, BigDecimal amountLcy, BigDecimal fxRate) {
        TransactionItemEntity item = new TransactionItemEntity();
        item.setId(id);
        item.setAmountFcy(amountFcy);
        item.setAmountLcy(amountLcy);
        item.setFxRate(fxRate);
        item.setOperationType(OperationType.DEBIT);
        item.setCostCenter(Optional.empty());
        item.setProject(Optional.empty());
        item.setAccountEvent(Optional.empty());
        item.setDocument(Optional.empty());
        return item;
    }
}
