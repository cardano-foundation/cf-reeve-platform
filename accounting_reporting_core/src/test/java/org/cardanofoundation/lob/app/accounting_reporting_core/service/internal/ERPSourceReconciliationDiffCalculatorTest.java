package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;

class ERPSourceReconciliationDiffCalculatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ERPSourceReconciliationDiffCalculator calculator;

    private static final LocalDate ENTRY_DATE = LocalDate.of(2024, 1, 15);
    private static final String ORG_ID = "org-abc";
    private static final String TX_NUMBER = "TX-001";
    private static final TransactionType TX_TYPE = TransactionType.VendorPayment;

    @BeforeEach
    void setUp() {
        calculator = new ERPSourceReconciliationDiffCalculator(objectMapper);
    }

    // ============== Helpers ==============

    private TransactionEntity buildTx(String txNumber, TransactionType type, LocalDate entryDate, String orgId) {
        TransactionEntity tx = new TransactionEntity();
        tx.setId("tx-id");
        tx.setInternalTransactionNumber(txNumber);
        tx.setTransactionType(type);
        tx.setEntryDate(entryDate);
        tx.setOrganisation(Organisation.builder().id(orgId).build());
        tx.setItems(Set.of());
        return tx;
    }

    private TransactionEntity baseTx() {
        return buildTx(TX_NUMBER, TX_TYPE, ENTRY_DATE, ORG_ID);
    }

    private TransactionItemEntity buildItem(String id, BigDecimal amountFcy, BigDecimal amountLcy, BigDecimal fxRate) {
        TransactionItemEntity item = new TransactionItemEntity();
        item.setId(id);
        item.setAmountFcy(amountFcy);
        item.setAmountLcy(amountLcy);
        item.setFxRate(fxRate);
        return item;
    }

    private TransactionEntity txWithItems(TransactionItemEntity... items) {
        TransactionEntity tx = baseTx();
        tx.setItems(Set.of(items));
        return tx;
    }

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    // ============== Transaction-level field tests ==============

    @Test
    void testComputeDiff_identical_producesEmptyDiff() throws Exception {
        TransactionEntity lob = baseTx();
        TransactionEntity erp = baseTx();

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void testComputeDiff_internalTransactionNumberMismatch() throws Exception {
        TransactionEntity lob = baseTx();
        TransactionEntity erp = buildTx("TX-002", TX_TYPE, ENTRY_DATE, ORG_ID);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode mismatches = result.get("txFieldMismatches");
        assertThat(mismatches).isNotNull().hasSize(1);
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("internalTransactionNumber");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("TX-001");
        assertThat(mismatches.get(0).get("erp").asText()).isEqualTo("TX-002");
    }

    @Test
    void testComputeDiff_transactionTypeMismatch() throws Exception {
        TransactionEntity lob = baseTx();
        TransactionEntity erp = buildTx(TX_NUMBER, TransactionType.Journal, ENTRY_DATE, ORG_ID);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode mismatches = result.get("txFieldMismatches");
        assertThat(mismatches).isNotNull().hasSize(1);
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("transactionType");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("VendorPayment");
        assertThat(mismatches.get(0).get("erp").asText()).isEqualTo("Journal");
    }

    @Test
    void testComputeDiff_entryDateMismatch() throws Exception {
        TransactionEntity lob = baseTx();
        TransactionEntity erp = buildTx(TX_NUMBER, TX_TYPE, ENTRY_DATE.plusDays(1), ORG_ID);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode mismatches = result.get("txFieldMismatches");
        assertThat(mismatches).isNotNull().hasSize(1);
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("entryDate");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("2024-01-15");
        assertThat(mismatches.get(0).get("erp").asText()).isEqualTo("2024-01-16");
    }

    @Test
    void testComputeDiff_organisationIdMismatch() throws Exception {
        TransactionEntity lob = baseTx();
        TransactionEntity erp = buildTx(TX_NUMBER, TX_TYPE, ENTRY_DATE, "org-xyz");

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode mismatches = result.get("txFieldMismatches");
        assertThat(mismatches).isNotNull().hasSize(1);
        assertThat(mismatches.get(0).get("field").asText()).isEqualTo("organisation.id");
        assertThat(mismatches.get(0).get("reeve").asText()).isEqualTo("org-abc");
        assertThat(mismatches.get(0).get("erp").asText()).isEqualTo("org-xyz");
    }

    @Test
    void testComputeDiff_multipleTransactionFieldMismatches() throws Exception {
        TransactionEntity lob = baseTx();
        TransactionEntity erp = buildTx("TX-002", TransactionType.Journal, ENTRY_DATE, ORG_ID);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode mismatches = result.get("txFieldMismatches");
        assertThat(mismatches).isNotNull().hasSize(2);
    }

    // ============== Item count mismatch ==============

    @Test
    void testComputeDiff_itemCountMismatch_lobHasMore() throws Exception {
        TransactionItemEntity item1 = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        TransactionItemEntity item2 = buildItem("item-2", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = baseTx();
        lob.setItems(Set.of(item1, item2));

        TransactionEntity erp = baseTx();
        erp.setItems(Set.of(item1));

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("itemCountMismatch")).isNotNull();
        assertThat(result.get("itemCountMismatch").get("reeve").asInt()).isEqualTo(2);
        assertThat(result.get("itemCountMismatch").get("erp").asInt()).isEqualTo(1);
    }

    @Test
    void testComputeDiff_itemCountMismatch_erpHasMore() throws Exception {
        TransactionItemEntity item1 = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        TransactionItemEntity item2 = buildItem("item-2", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = baseTx();
        lob.setItems(Set.of(item1));

        TransactionEntity erp = baseTx();
        erp.setItems(Set.of(item1, item2));

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("itemCountMismatch")).isNotNull();
        assertThat(result.get("itemCountMismatch").get("reeve").asInt()).isEqualTo(1);
        assertThat(result.get("itemCountMismatch").get("erp").asInt()).isEqualTo(2);
    }

    @Test
    void testComputeDiff_sameItemCount_noCountMismatch() throws Exception {
        TransactionItemEntity item = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = txWithItems(item);
        TransactionEntity erp = txWithItems(item);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("itemCountMismatch")).isNull();
    }

    // ============== Item existence tests ==============

    @Test
    void testComputeDiff_itemOnlyInLob() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = baseTx();

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode itemDiffs = result.get("itemDiffs");
        assertThat(itemDiffs).isNotNull().hasSize(1);
        assertThat(itemDiffs.get(0).get("itemId").asText()).isEqualTo("item-1");
        assertThat(itemDiffs.get(0).get("onlyInLob").asBoolean()).isTrue();
        assertThat(itemDiffs.get(0).get("onlyInErp")).isNull();
        assertThat(itemDiffs.get(0).get("fieldMismatches")).isNull();
    }

    @Test
    void testComputeDiff_itemOnlyInErp() throws Exception {
        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = baseTx();
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode itemDiffs = result.get("itemDiffs");
        assertThat(itemDiffs).isNotNull().hasSize(1);
        assertThat(itemDiffs.get(0).get("itemId").asText()).isEqualTo("item-1");
        assertThat(itemDiffs.get(0).get("onlyInErp").asBoolean()).isTrue();
        assertThat(itemDiffs.get(0).get("onlyInLob")).isNull();
        assertThat(itemDiffs.get(0).get("fieldMismatches")).isNull();
    }

    @Test
    void testComputeDiff_identicalItems_noItemDiffs() throws Exception {
        TransactionItemEntity item = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        item.setAccountCredit(Optional.of(Account.builder().code("ACC-100").build()));
        item.setAccountDebit(Optional.of(Account.builder().code("ACC-200").build()));

        TransactionEntity lob = txWithItems(item);
        TransactionEntity erp = txWithItems(item);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("itemDiffs")).isNull();
    }

    // ============== Item field-level mismatch tests ==============

    @Test
    void testComputeDiff_itemAmountFcyMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", new BigDecimal("100.00"), BigDecimal.TEN, BigDecimal.ONE);
        TransactionItemEntity erpItem = buildItem("item-1", new BigDecimal("200.00"), BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("amountFcy");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("100");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("200");
    }

    @Test
    void testComputeDiff_itemAmountLcyMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, new BigDecimal("50.00"), BigDecimal.ONE);
        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, new BigDecimal("60.00"), BigDecimal.ONE);

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("amountLcy");
    }

    @Test
    void testComputeDiff_itemFxRateMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("1.10"));
        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("1.20"));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("fxRate");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("1.1");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("1.2");
    }

    @Test
    void testComputeDiff_itemAccountCreditMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setAccountCredit(Optional.of(Account.builder().code("ACC-100").build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setAccountCredit(Optional.of(Account.builder().code("ACC-999").build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("accountCredit.code");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("ACC-100");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("ACC-999");
    }

    @Test
    void testComputeDiff_itemAccountDebitMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setAccountDebit(Optional.of(Account.builder().code("DEB-100").build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setAccountDebit(Optional.of(Account.builder().code("DEB-200").build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("accountDebit.code");
    }

    @Test
    void testComputeDiff_itemCostCenterMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setCostCenter(Optional.of(CostCenter.builder().customerCode("CC-100").build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setCostCenter(Optional.of(CostCenter.builder().customerCode("CC-200").build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("costCenter.customerCode");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("CC-100");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("CC-200");
    }

    @Test
    void testComputeDiff_itemProjectMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setProject(Optional.of(Project.builder().customerCode("PRJ-A").build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setProject(Optional.of(Project.builder().customerCode("PRJ-B").build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("project.customerCode");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("PRJ-A");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("PRJ-B");
    }

    // ============== Document field tests ==============

    @Test
    void testComputeDiff_documentNumMismatch() throws Exception {
        Currency currency = Currency.builder().customerCode("CHF").build();

        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency).build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setDocument(Optional.of(Document.builder().num("DOC-999").currency(currency).build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("document.num");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("DOC-001");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("DOC-999");
    }

    @Test
    void testComputeDiff_documentCurrencyMismatch() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setDocument(Optional.of(Document.builder().num("DOC-001")
                .currency(Currency.builder().customerCode("CHF").build()).build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setDocument(Optional.of(Document.builder().num("DOC-001")
                .currency(Currency.builder().customerCode("USD").build()).build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("document.currency.customerCode");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("CHF");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("USD");
    }

    @Test
    void testComputeDiff_documentCounterpartyMismatch() throws Exception {
        Currency currency = Currency.builder().customerCode("CHF").build();

        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency)
                .counterparty(Counterparty.builder().customerCode("CP-A").build()).build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency)
                .counterparty(Counterparty.builder().customerCode("CP-B").build()).build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("document.counterparty.customerCode");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("CP-A");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("CP-B");
    }

    @Test
    void testComputeDiff_documentVatMismatch() throws Exception {
        Currency currency = Currency.builder().customerCode("CHF").build();

        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency)
                .vat(Vat.builder().customerCode("VAT-A").build()).build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency)
                .vat(Vat.builder().customerCode("VAT-B").build()).build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("document.vat.customerCode");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("VAT-A");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("VAT-B");
    }

    @Test
    void testComputeDiff_documentPresentInLobAbsentInErp() throws Exception {
        Currency currency = Currency.builder().customerCode("CHF").build();

        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        lobItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency).build()));

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        // no document in ERP item

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("document");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("present");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("absent");
    }

    @Test
    void testComputeDiff_documentAbsentInLobPresentInErp() throws Exception {
        Currency currency = Currency.builder().customerCode("CHF").build();

        TransactionItemEntity lobItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        // no document in LOB item

        TransactionItemEntity erpItem = buildItem("item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE);
        erpItem.setDocument(Optional.of(Document.builder().num("DOC-001").currency(currency).build()));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("document");
        assertThat(fieldMismatches.get(0).get("reeve").asText()).isEqualTo("absent");
        assertThat(fieldMismatches.get(0).get("erp").asText()).isEqualTo("present");
    }

    // ============== Rollback transaction ==============

    @Test
    void testComputeDiff_rollbackTransaction_itemDiffSkipped() throws Exception {
        TransactionEntity lob = baseTx();
        lob.setRollbackSuffix("C");
        // item IDs differ for rollback — two different items
        lob.setItems(Set.of(buildItem("lob-item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE)));

        TransactionEntity erp = buildTx(TX_NUMBER, TX_TYPE, ENTRY_DATE, ORG_ID);
        erp.setItems(Set.of(buildItem("erp-item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE)));

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("rollbackTransaction").asBoolean()).isTrue();
        assertThat(result.get("itemDiffs")).isNull();
    }

    @Test
    void testComputeDiff_rollbackTransaction_stillReportsItemCountMismatch() throws Exception {
        TransactionEntity lob = baseTx();
        lob.setRollbackSuffix("C");
        lob.setItems(Set.of(
                buildItem("lob-item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE),
                buildItem("lob-item-2", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE)
        ));

        TransactionEntity erp = buildTx(TX_NUMBER, TX_TYPE, ENTRY_DATE, ORG_ID);
        erp.setItems(Set.of(buildItem("erp-item-1", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE)));

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("rollbackTransaction").asBoolean()).isTrue();
        assertThat(result.get("itemCountMismatch")).isNotNull();
        assertThat(result.get("itemCountMismatch").get("reeve").asInt()).isEqualTo(2);
        assertThat(result.get("itemCountMismatch").get("erp").asInt()).isEqualTo(1);
    }

    @Test
    void testComputeDiff_nonRollbackTransaction_rollbackTransactionFieldAbsent() throws Exception {
        JsonNode result = parse(calculator.computeDiff(baseTx(), baseTx()));

        assertThat(result.get("rollbackTransaction")).isNull();
    }

    // ============== BigDecimal normalization ==============

    @Test
    void testComputeDiff_bigDecimalSameValueDifferentScale_noDiff() throws Exception {
        // 100.00 and 100.0000000 normalize to the same value — should not produce a mismatch
        TransactionItemEntity lobItem = buildItem("item-1",
                new BigDecimal("100.00"), new BigDecimal("50.0000"), new BigDecimal("1.0"));
        TransactionItemEntity erpItem = buildItem("item-1",
                new BigDecimal("100.0000"), new BigDecimal("50.00"), new BigDecimal("1.00000000"));

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        assertThat(result.get("itemDiffs")).isNull();
    }

    @Test
    void testComputeDiff_bigDecimalDifferentValue_producesDiff() throws Exception {
        TransactionItemEntity lobItem = buildItem("item-1",
                new BigDecimal("100.00000001"), BigDecimal.TEN, BigDecimal.ONE);
        TransactionItemEntity erpItem = buildItem("item-1",
                new BigDecimal("100.00000002"), BigDecimal.TEN, BigDecimal.ONE);

        TransactionEntity lob = txWithItems(lobItem);
        TransactionEntity erp = txWithItems(erpItem);

        JsonNode result = parse(calculator.computeDiff(lob, erp));

        JsonNode fieldMismatches = result.get("itemDiffs").get(0).get("fieldMismatches");
        assertThat(fieldMismatches).isNotNull().hasSize(1);
        assertThat(fieldMismatches.get(0).get("field").asText()).isEqualTo("amountFcy");
    }

    // ============== JSON structure ==============

    @Test
    void testComputeDiff_outputIsValidJson() {
        String json = calculator.computeDiff(baseTx(), baseTx());

        assertThat(json).isNotBlank();
        assertThat(json).startsWith("{");
        assertThat(json).endsWith("}");
    }

    @Test
    void testComputeDiff_nullFieldsOmittedFromJson_whenNoMismatch() throws Exception {
        JsonNode result = parse(calculator.computeDiff(baseTx(), baseTx()));

        assertThat(result.get("txFieldMismatches")).isNull();
        assertThat(result.get("itemCountMismatch")).isNull();
        assertThat(result.get("itemDiffs")).isNull();
        assertThat(result.get("rollbackTransaction")).isNull();
    }

}
