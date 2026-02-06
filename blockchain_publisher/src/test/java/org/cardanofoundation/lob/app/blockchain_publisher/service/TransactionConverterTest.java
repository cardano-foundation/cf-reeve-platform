package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.AccountEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Document.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Project;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Vat;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionItemEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionConverter Tests")
class TransactionConverterTest {

    @Mock
    private BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    @Mock
    private OrganisationPublicApi organisationPublicApi;

    private TransactionConverter transactionConverter;

    @BeforeEach
    void setUp() {
        transactionConverter = new TransactionConverter(blockchainPublishStatusMapper, organisationPublicApi);
    }

    // ============ convertToDbDetached(Transaction tx) Tests ============

    @Test
    @DisplayName("Should convert transaction with basic fields")
    void testConvertToDbDetached_BasicFields() {
        // Arrange
        Transaction tx = createTestTransaction("tx-1", "INT-001", "batch-1", TransactionType.Journal);
        when(blockchainPublishStatusMapper.convert(any(LedgerDispatchStatus.class)))
                .thenReturn(BlockchainPublishStatus.STORED);

        // Act
        TransactionEntity result = transactionConverter.convertToDbDetached(tx);

        // Assert
        assertAll(
                () -> assertThat(result.getId()).isEqualTo("tx-1"),
                () -> assertThat(result.getInternalNumber()).isEqualTo("INT-001"),
                () -> assertThat(result.getBatchId()).isEqualTo("batch-1"),
                () -> assertThat(result.getTransactionType()).isEqualTo(TransactionType.Journal),
                () -> assertThat(result.getEntryDate()).isEqualTo(LocalDate.of(2024, 1, 15)),
                () -> assertThat(result.getAccountingPeriod()).isEqualTo(YearMonth.of(2024, 1))
        );
    }

    @Test
    @DisplayName("Should set L1SubmissionData with correct publish status")
    void testConvertToDbDetached_L1SubmissionData() {
        // Arrange
        Transaction tx = createTestTransaction("tx-2", "INT-002", "batch-2", TransactionType.Journal);
        when(blockchainPublishStatusMapper.convert(any(LedgerDispatchStatus.class)))
                .thenReturn(BlockchainPublishStatus.STORED);

        // Act
        TransactionEntity result = transactionConverter.convertToDbDetached(tx);

        // Assert
        assertThat(result.getL1SubmissionData()).isPresent();
    }

    @Test
    @DisplayName("Should convert organisation correctly")
    void testConvertToDbDetached_Organisation() {
        // Arrange
        Transaction tx = createTestTransaction("tx-3", "INT-003", "batch-3", TransactionType.Journal);
        when(blockchainPublishStatusMapper.convert(any(LedgerDispatchStatus.class)))
                .thenReturn(BlockchainPublishStatus.STORED);

        // Act
        TransactionEntity result = transactionConverter.convertToDbDetached(tx);

        // Assert
        assertAll(
                () -> assertThat(result.getOrganisation()).isNotNull(),
                () -> assertThat(result.getOrganisation().getId()).isEqualTo("org-1"),
                () -> assertThat(result.getOrganisation().getName()).isEqualTo("Test Organisation"),
                () -> assertThat(result.getOrganisation().getCountryCode()).isEqualTo("US"),
                () -> assertThat(result.getOrganisation().getTaxIdNumber()).isEqualTo("TAX123"),
                () -> assertThat(result.getOrganisation().getCurrencyId()).isEqualTo("USD")
        );
    }

    @Test
    @DisplayName("Should aggregate transaction items with same hash")
    void testConvertToDbDetached_AggregateItems() {
        // Arrange
        Transaction tx = createTestTransactionWithItems("tx-4", "INT-004", "batch-4", 2);
        when(blockchainPublishStatusMapper.convert(any(LedgerDispatchStatus.class)))
                .thenReturn(BlockchainPublishStatus.STORED);

        // Act
        TransactionEntity result = transactionConverter.convertToDbDetached(tx);

        // Assert
        assertThat(result.getItems()).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle transaction with no items")
    void testConvertToDbDetached_NoItems() {
        // Arrange
        Transaction tx = createTestTransaction("tx-5", "INT-005", "batch-5", TransactionType.Journal);
        when(blockchainPublishStatusMapper.convert(any(LedgerDispatchStatus.class)))
                .thenReturn(BlockchainPublishStatus.STORED);

        // Act
        TransactionEntity result = transactionConverter.convertToDbDetached(tx);

        // Assert
        assertThat(result.getItems()).isEmpty();
    }

    // ============ convertToDbDetached(TransactionEntity parent, TransactionItem txItem) Tests ============

    @Test
    @DisplayName("Should convert transaction item with basic fields")
    void testConvertToDbDetached_TransactionItem_BasicFields() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertAll(
                () -> assertThat(result.getId()).isEqualTo("item-1"),
                () -> assertThat(result.getTransaction()).isEqualTo(parent),
                () -> assertThat(result.getAmountFcy()).isEqualTo(new BigDecimal("100.00")),
                () -> assertThat(result.getFxRate()).isEqualTo(new BigDecimal("1.20"))
        );
    }

    @Test
    @DisplayName("Should set account event when present")
    void testConvertToDbDetached_TransactionItem_WithAccountEvent() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getAccountEvent()).isPresent();
        var accountEvent = result.getAccountEvent().get();
        assertThat(accountEvent.getCode()).isEqualTo("AE001");
        assertThat(accountEvent.getName()).isEqualTo("Test Event");
    }

    @Test
    @DisplayName("Should set document correctly")
    void testConvertToDbDetached_TransactionItem_WithDocument() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getDocument()).isNotNull();
    }

    @Test
    @DisplayName("Should set amount LCY when organisation currency differs from document currency")
    void testConvertToDbDetached_TransactionItem_DifferentCurrency() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation mockOrg =
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(mockOrg.getCurrencyId()).thenReturn("EUR");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.of(mockOrg));

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getAmountLcy()).isEqualTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("Should not set amount LCY when organisation currency matches document currency")
    void testConvertToDbDetached_TransactionItem_SameCurrency() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation mockOrg =
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        // CoreCurrency.toExternalId() returns "ISO_4217:USD" format
        when(mockOrg.getCurrencyId()).thenReturn("ISO_4217:USD");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.of(mockOrg));

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert - amountLcy should not be set when currencies match
        assertThat(result.getAmountLcy()).isNull();
    }

    @Test
    @DisplayName("Should override amount FCY for FxRevaluation transaction type")
    void testConvertToDbDetached_TransactionItem_FxRevaluation() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        parent.setTransactionType(TransactionType.FxRevaluation);
        TransactionItem txItem = createTestTransactionItem("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getAmountFcy()).isEqualTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("Should set cost center when present and associated with organisation")
    void testConvertToDbDetached_TransactionItem_WithCostCenter() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation mockOrg =
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(mockOrg.getCurrencyId()).thenReturn("USD");

        CostCenter mockCostCenter = mock(CostCenter.class);
        when(mockCostCenter.getParent()).thenReturn(Optional.empty());

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.of(mockOrg));
        when(organisationPublicApi.findCostCenter("org-1", "CC001"))
                .thenReturn(Optional.of(mockCostCenter));

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getCostCenter()).isPresent();
        var costCenter = result.getCostCenter().get();
        assertThat(costCenter.getCustomerCode()).isEqualTo("CC001");
    }

    @Test
    @DisplayName("Should use parent cost center when present")
    void testConvertToDbDetached_TransactionItem_WithParentCostCenter() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation mockOrg =
                mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(mockOrg.getCurrencyId()).thenReturn("USD");

        CostCenter mockParentCostCenter = mock(CostCenter.class);
        CostCenter.Id parentId = mock(CostCenter.Id.class);
        when(parentId.getCustomerCode()).thenReturn("PARENT-CC");
        when(mockParentCostCenter.getId()).thenReturn(parentId);
        when(mockParentCostCenter.getName()).thenReturn("Parent Cost Center");

        CostCenter mockCostCenter = mock(CostCenter.class);
        when(mockCostCenter.getParent()).thenReturn(Optional.of(mockParentCostCenter));

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.of(mockOrg));
        when(organisationPublicApi.findCostCenter("org-1", "CC001"))
                .thenReturn(Optional.of(mockCostCenter));

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getCostCenter()).isPresent();
        var costCenter = result.getCostCenter().get();
        assertThat(costCenter.getCustomerCode()).isEqualTo("PARENT-CC");
        assertThat(costCenter.getName()).isEqualTo("Parent Cost Center");
    }

    @Test
    @DisplayName("Should not set cost center when customer code is null")
    void testConvertToDbDetached_TransactionItem_CostCenterWithNullCustomerCode() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItemWithoutCostCenter("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getCostCenter()).isEmpty();
    }

    @Test
    @DisplayName("Should set project when present")
    void testConvertToDbDetached_TransactionItem_WithProject() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createTestTransactionItem("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertThat(result.getProject()).isPresent();
        var project = result.getProject().get();
        assertThat(project.getCustomerCode()).isEqualTo("PROJ001");
        assertThat(project.getName()).isEqualTo("Test Project");
    }

    @Test
    @DisplayName("Should handle transaction item without optional fields")
    void testConvertToDbDetached_TransactionItem_MinimalFields() {
        // Arrange
        TransactionEntity parent = createTestTransactionEntity("tx-1", "org-1");
        TransactionItem txItem = createMinimalTransactionItem("item-1");

        when(organisationPublicApi.findByOrganisationId("org-1"))
                .thenReturn(Optional.empty());

        // Act
        TransactionItemEntity result = transactionConverter.convertToDbDetached(parent, txItem);

        // Assert
        assertAll(
                () -> assertThat(result.getId()).isEqualTo("item-1"),
                () -> assertThat(result.getTransaction()).isEqualTo(parent),
                () -> assertThat(result.getAccountEvent()).isEmpty(),
                () -> assertThat(result.getCostCenter()).isEmpty(),
                () -> assertThat(result.getProject()).isEmpty()
        );
    }

    // ============ Helper Methods ============

    private Transaction createTestTransaction(String id, String internalNumber, String batchId, TransactionType type) {
        Organisation org = Organisation.builder()
                .id("org-1")
                .name(Optional.of("Test Organisation"))
                .countryCode(Optional.of("US"))
                .taxIdNumber(Optional.of("TAX123"))
                .currencyId("USD")
                .build();

        return Transaction.builder()
                .id(id)
                .internalTransactionNumber(internalNumber)
                .batchId(batchId)
                .transactionType(type)
                .organisation(org)
                .entryDate(LocalDate.of(2024, 1, 15))
                .accountingPeriod(YearMonth.of(2024, 1))
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .items(new HashSet<>())
                .build();
    }

    private Transaction createTestTransactionWithItems(String id, String internalNumber, String batchId, int itemCount) {
        Organisation org = Organisation.builder()
                .id("org-1")
                .name(Optional.of("Test Organisation"))
                .countryCode(Optional.of("US"))
                .taxIdNumber(Optional.of("TAX123"))
                .currencyId("USD")
                .build();

        Set<TransactionItem> items = new HashSet<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(createTestTransactionItem("item-" + i));
        }

        return Transaction.builder()
                .id(id)
                .internalTransactionNumber(internalNumber)
                .batchId(batchId)
                .transactionType(TransactionType.Journal)
                .organisation(org)
                .entryDate(LocalDate.of(2024, 1, 15))
                .accountingPeriod(YearMonth.of(2024, 1))
                .ledgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED)
                .items(items)
                .build();
    }

    private TransactionItem createTestTransactionItem(String id) {
        AccountEvent accountEvent = AccountEvent.builder()
                .code("AE001")
                .name("Test Event")
                .build();

        CoreCurrency coreCurrency = CoreCurrency.builder()
                .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                .currencyISOCode("USD")
                .name("US Dollar")
                .build();

        org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency currency =
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                        .coreCurrency(Optional.of(coreCurrency))
                        .customerCode("USD")
                        .build();

        Document document = Document.builder()
                .number("DOC-001")
                .currency(currency)
                .vat(Optional.empty())
                .counterparty(Optional.empty())
                .build();

        org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter costCenter =
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder()
                .customerCode("CC001")
                .name(Optional.of("Test Cost Center"))
                .build();

        Project project = Project.builder()
                .customerCode("PROJ001")
                .name(Optional.of("Test Project"))
                .build();

        return TransactionItem.builder()
                .id(id)
                .accountEvent(Optional.of(accountEvent))
                .fxRate(new BigDecimal("1.20"))
                .amountFcy(new BigDecimal("100.00"))
                .amountLcy(new BigDecimal("120.00"))
                .document(Optional.of(document))
                .costCenter(Optional.of(costCenter))
                .project(Optional.of(project))
                .build();
    }

    private TransactionItem createTestTransactionItemWithoutCostCenter(String id) {
        AccountEvent accountEvent = AccountEvent.builder()
                .code("AE001")
                .name("Test Event")
                .build();

        CoreCurrency coreCurrency = CoreCurrency.builder()
                .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                .currencyISOCode("USD")
                .name("US Dollar")
                .build();

        org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency currency =
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                        .coreCurrency(Optional.of(coreCurrency))
                        .customerCode("USD")
                        .build();

        Document document = Document.builder()
                .number("DOC-001")
                .currency(currency)
                .vat(Optional.empty())
                .counterparty(Optional.empty())
                .build();

        return TransactionItem.builder()
                .id(id)
                .accountEvent(Optional.of(accountEvent))
                .fxRate(new BigDecimal("1.20"))
                .amountFcy(new BigDecimal("100.00"))
                .amountLcy(new BigDecimal("120.00"))
                .document(Optional.of(document))
                .costCenter(Optional.empty())
                .project(Optional.empty())
                .build();
    }

    private TransactionItem createMinimalTransactionItem(String id) {
        CoreCurrency coreCurrency = CoreCurrency.builder()
                .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                .currencyISOCode("USD")
                .name("US Dollar")
                .build();

        org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency currency =
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                        .coreCurrency(Optional.of(coreCurrency))
                        .customerCode("USD")
                        .build();

        Document document = Document.builder()
                .number("DOC-001")
                .currency(currency)
                .vat(Optional.empty())
                .counterparty(Optional.empty())
                .build();

        return TransactionItem.builder()
                .id(id)
                .accountEvent(Optional.empty())
                .fxRate(new BigDecimal("1.00"))
                .amountFcy(new BigDecimal("100.00"))
                .amountLcy(null)
                .document(Optional.of(document))
                .costCenter(Optional.empty())
                .project(Optional.empty())
                .build();
    }

    @Test
    @DisplayName("Should convert CardCharge transaction with multiple items correctly")
    void testConvertCardChargeTransaction() {
        // Arrange
        String txId = "c8250b476d2c954442eda43995a9f4e383cb87776aeb269432867cbabcab5268";
        String orgId = "97c6baed084f17579edb1d4dfc285360f4984fc9ac2af550c70c7c9cb5df8f8e";

        // Create organisation
        org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation testOrg =
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation.builder()
                        .id(orgId)
                        .name(Optional.of("Foundation Fundada"))
                        .countryCode(Optional.of("CH"))
                        .taxIdNumber(Optional.of("CHE-184477354"))
                        .currencyId("ISO_4217:CHF")
                        .build();

        // Mock organisation API response
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(
                org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.builder()
                        .id(orgId)
                        .currencyId("ISO_4217:CHF")
                        .build()
        ));

        when(organisationPublicApi.findCostCenter(eq(orgId), anyString())).thenReturn(
                Optional.of(CostCenter.builder().name("este").parent(CostCenter.builder().id(new CostCenter.Id(orgId,"9000")).build()).build())
        );

        // Create transaction
        Transaction tx = Transaction.builder()
                .id(txId)
                .internalTransactionNumber("CARDCHRG-53efb89f61")
                .batchId("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .transactionType(TransactionType.CardCharge)
                .organisation(testOrg)
                .entryDate(LocalDate.of(2025, 4, 21))
                .accountingPeriod(YearMonth.of(2025, 4))
                .transactionApproved(true)
                .ledgerDispatchApproved(true)
                .ledgerDispatchStatus(LedgerDispatchStatus.FINALIZED)
                .items(new HashSet<>())
                .build();

        // Add transaction items
        addCardChargeItems(tx);

        when(blockchainPublishStatusMapper.convert(any(LedgerDispatchStatus.class)))
                .thenReturn(BlockchainPublishStatus.STORED);

        // Act
        TransactionEntity result = transactionConverter.convertToDbDetached(tx);

        // Assert
        assertAll(
                () -> assertThat(result.getId()).isEqualTo(txId),
                () -> assertThat(result.getInternalNumber()).isEqualTo("CARDCHRG-53efb89f61"),
                () -> assertThat(result.getTransactionType()).isEqualTo(TransactionType.CardCharge),
                () -> assertThat(result.getEntryDate()).isEqualTo(LocalDate.of(2025, 4, 21)),
                () -> assertThat(result.getAccountingPeriod()).isEqualTo(YearMonth.of(2025, 4)),
                () -> assertThat(result.getItems()).hasSize(5),
                () -> assertThat(result.getItems())
                        .extracting(TransactionItemEntity::getAmountFcy)
                        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                        .containsExactlyInAnyOrder(
                                new BigDecimal("372239.78"),
                                new BigDecimal("372239.78"),
                                new BigDecimal("155268.00"),
                                new BigDecimal("372239.78"),
                                new BigDecimal("744479.56")
                        ),
                () -> assertThat(result.getL1SubmissionData()).isPresent(),
                () -> assertThat(result.getL1SubmissionData().get().getPublishStatus())
                        .isEqualTo(Optional.of(BlockchainPublishStatus.STORED))
        );
    }

    private void addCardChargeItems(Transaction tx) {
        tx.getItems().add(createCardChargeItem(
                "06313e5e704b7cd2c96d0de34e9cb976678c1fca58660f9c343653f0d889ac79",
                new BigDecimal("2.000000000000000"),
                Account.builder().code("0000000000").name(Optional.of("Account-0000000000")).refCode(Optional.of("T000")).build(),
                Account.builder().code("6421150100").name(Optional.of("6421150100")).refCode(Optional.of("8240")).build(),
                AccountEvent.builder().code("64211501008240").name("Financial Revenues - Transfer acc/Crypto related revenues").build(),
                new BigDecimal("372239.780000000000000"),
                new BigDecimal("186119.890000000000000"),
                Document.builder()
                        .number("id-344171")
                        .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                                .customerCode("USD")
                                .coreCurrency(Optional.of(CoreCurrency.builder()
                                        .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                                        .currencyISOCode("USD")
                                        .name("ISO_4217:USD")
                                        .build()))
                                .build())
                        .vat(Optional.of(Vat.builder().customerCode("VAT3").rate(Optional.of(new BigDecimal("0.000000000000000"))).build()))
                        .counterparty(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.builder()
                                .customerCode("0392")
                                .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                                .name(Optional.of("Company Paj3te"))
                                .build()))
                        .build(),
                Project.builder().customerCode("IdentityTech").name(Optional.of("IdentityTech")).build(),
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder().customerCode("7200").name(Optional.of("Engineering & Development")).build(),
                OperationType.DEBIT
        ));

        tx.getItems().add(createCardChargeItem(
                "16f719d5b85f1baf6e9e251d426402ba0ae9035e0155f23879ba02966dbd5b7c",
                new BigDecimal("2.000000000000000"),
                Account.builder().code("1206400100").name(Optional.of("Account-1206400100")).refCode(Optional.of("1600")).build(),
                Account.builder().code("2101110101").name(Optional.of("2101110101")).refCode(Optional.of("3100")).build(),
                AccountEvent.builder().code("21011101013100").name("VAT on Purchases - Tax Assets VAT/Accounts payable").build(),
                new BigDecimal("155268.000000000000000"),
                new BigDecimal("77634.000000000000000"),
                Document.builder()
                        .number("id-344171")
                        .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                                .customerCode("USD")
                                .coreCurrency(Optional.of(CoreCurrency.builder()
                                        .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                                        .currencyISOCode("USD")
                                        .name("ISO_4217:USD")
                                        .build()))
                                .build())
                        .vat(Optional.of(Vat.builder().customerCode("VAT3").rate(Optional.of(new BigDecimal("0.000000000000000"))).build()))
                        .counterparty(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.builder()
                                .customerCode("0392")
                                .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                                .name(Optional.of("Company Paj4te"))
                                .build()))
                        .build(),
                Project.builder().customerCode("IdentityTech").name(Optional.of("IdentityTech")).build(),
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder().customerCode("5100").name(Optional.of("Finance & Controlling")).build(),
                OperationType.DEBIT
        ));

        tx.getItems().add(createCardChargeItem(
                "366ce434af605b94a91ab1cb979b46e213a49d583efbcc89c91686fa05148f94",
                new BigDecimal("2.000000000000000"),
                Account.builder().code("5208120100").name(Optional.of("Account-5208120100")).refCode(Optional.of("6100")).build(),
                Account.builder().code("0000000000").name(Optional.of("0000000000")).refCode(Optional.of("T000")).build(),
                AccountEvent.builder().code("0000000000T000").name("COGS Expenses - Product Costs/Transfer acc").build(),
                new BigDecimal("372239.780000000000000"),
                new BigDecimal("186119.890000000000000"),
                Document.builder()
                        .number("id-344171")
                        .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                                .customerCode("USD")
                                .coreCurrency(Optional.of(CoreCurrency.builder()
                                        .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                                        .currencyISOCode("USD")
                                        .name("ISO_4217:USD")
                                        .build()))
                                .build())
                        .vat(Optional.of(Vat.builder().customerCode("VAT3").rate(Optional.of(new BigDecimal("0.000000000000000"))).build()))
                        .counterparty(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.builder()
                                .customerCode("0392")
                                .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                                .name(Optional.of("Company Paj6te"))
                                .build()))
                        .build(),
                Project.builder().customerCode("IdentityTech").name(Optional.of("IdentityTech")).build(),
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder().customerCode("4310").name(Optional.of("Engineering and Development")).build(),
                OperationType.DEBIT
        ));

        tx.getItems().add(createCardChargeItem(
                "5917bab5b62127d1c94e13b26e9f7117814208798cc4dd0a020634591625714a",
                new BigDecimal("2.000000000000000"),
                Account.builder().code("1206530100").name(Optional.of("Account-1206530100")).refCode(Optional.of("2510")).build(),
                Account.builder().code("0000000000").name(Optional.of("0000000000")).refCode(Optional.of("T000")).build(),
                AccountEvent.builder().code("0000000000T000").name("Long-term receivable - Long term receivable/Transfer acc").build(),
                new BigDecimal("372239.780000000000000"),
                new BigDecimal("186119.890000000000000"),
                Document.builder()
                        .number("id-344171")
                        .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                                .customerCode("USD")
                                .coreCurrency(Optional.of(CoreCurrency.builder()
                                        .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                                        .currencyISOCode("USD")
                                        .name("ISO_4217:USD")
                                        .build()))
                                .build())
                        .vat(Optional.of(Vat.builder().customerCode("VAT3").rate(Optional.of(new BigDecimal("0.000000000000000"))).build()))
                        .counterparty(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.builder()
                                .customerCode("0392")
                                .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                                .name(Optional.of("Company Paj6te"))
                                .build()))
                        .build(),
                Project.builder().customerCode("IdentityTech").name(Optional.of("IdentityTech")).build(),
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder().customerCode("5100").name(Optional.of("Finance & Controlling")).build(),
                OperationType.DEBIT
        ));

        tx.getItems().add(createCardChargeItem(
                "d73368d4fe923ab259e7f983cdbdf1c9f22974b0022f445fc2388749b5e0b35f",
                new BigDecimal("2.000000000000000"),
                Account.builder().code("6421270100").name(Optional.of("Account-6421270100")).refCode(Optional.of("8210")).build(),
                Account.builder().code("0000000000").name(Optional.of("0000000000")).refCode(Optional.of("T000")).build(),
                AccountEvent.builder().code("0000000000T000").name("Financial income transfer - Interest income/Transfer acc").build(),
                new BigDecimal("372239.780000000000000"),
                new BigDecimal("186119.890000000000000"),
                Document.builder()
                        .number("id-344171")
                        .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                                .customerCode("USD")
                                .coreCurrency(Optional.of(CoreCurrency.builder()
                                        .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                                        .currencyISOCode("USD")
                                        .name("ISO_4217:USD")
                                        .build()))
                                .build())
                        .vat(Optional.of(Vat.builder().customerCode("VAT3").rate(Optional.of(new BigDecimal("0.000000000000000"))).build()))
                        .counterparty(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.builder()
                                .customerCode("0392")
                                .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                                .name(Optional.of("Company Paj4te"))
                                .build()))
                        .build(),
                Project.builder().customerCode("IdentityTech").name(Optional.of("IdentityTech")).build(),
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder().customerCode("5100").name(Optional.of("Finance & Controlling")).build(),
                OperationType.DEBIT
        ));

        tx.getItems().add(createCardChargeItem(
                "7e43fba104a618cee65ba2b3814b44e28eb5602013fe8921c92b65de2c93d7fc",
                new BigDecimal("2.000000000000000"),
                Account.builder().code("6421270100").name(Optional.of("Account-6421270100")).refCode(Optional.of("8210")).build(),
                Account.builder().code("0000000000").name(Optional.of("0000000000")).refCode(Optional.of("T000")).build(),
                AccountEvent.builder().code("0000000000T000").name("Financial income transfer - Interest income/Transfer acc").build(),
                new BigDecimal("372239.780000000000000"),
                new BigDecimal("186119.890000000000000"),
                Document.builder()
                        .number("id-344171")
                        .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency.builder()
                                .customerCode("USD")
                                .coreCurrency(Optional.of(CoreCurrency.builder()
                                        .currencyISOStandard(CoreCurrency.IsoStandard.ISO_4217)
                                        .currencyISOCode("USD")
                                        .name("ISO_4217:USD")
                                        .build()))
                                .build())
                        .vat(Optional.of(Vat.builder().customerCode("VAT3").rate(Optional.of(new BigDecimal("0.000000000000000"))).build()))
                        .counterparty(Optional.of(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.builder()
                                .customerCode("0392")
                                .type(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR)
                                .name(Optional.of("Company Paj10te"))
                                .build()))
                        .build(),
                Project.builder().customerCode("IdentityTech").name(Optional.of("IdentityTech")).build(),
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter.builder().customerCode("7200").name(Optional.of("Engineering & Development")).build(),
                OperationType.DEBIT
        ));

    }

    private TransactionItem createCardChargeItem(
            String id,
            BigDecimal fxRate,
            Account accountDebit,
            Account accountCredit,
            AccountEvent accountEvent,
            BigDecimal amountFcy,
            BigDecimal amountLcy,
            Document document,
            Project project,
            org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter costCenter,
            OperationType operationType) {
        return TransactionItem.builder()
                .id(id)
                .accountDebit(Optional.of(accountDebit))
                .accountCredit(Optional.of(accountCredit))
                .accountEvent(Optional.of(accountEvent))
                .fxRate(fxRate)
                .amountFcy(amountFcy)
                .amountLcy(amountLcy)
                .document(Optional.of(document))
                .project(Optional.of(project))
                .costCenter(Optional.of(costCenter))
                .operationType(operationType)
                .build();
    }

    private TransactionEntity createTestTransactionEntity(String id, String organisationId) {
        org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation orgEntity =
                org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation.builder()
                        .id(organisationId)
                        .name("Test Organisation")
                        .countryCode("US")
                        .taxIdNumber("TAX123")
                        .currencyId("USD")
                        .build();

        return TransactionEntity.builder()
                .id(id)
                .internalNumber("INT-001")
                .batchId("batch-1")
                .transactionType(TransactionType.Journal)
                .organisation(orgEntity)
                .entryDate(LocalDate.of(2024, 1, 15))
                .accountingPeriod(YearMonth.of(2024, 1))
                .items(new HashSet<>())
                .build();
    }
}
