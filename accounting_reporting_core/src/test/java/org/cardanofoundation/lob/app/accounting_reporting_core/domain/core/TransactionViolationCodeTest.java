package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class TransactionViolationCodeTest {

    @Test
    void exclusions_shouldContainTransactionNotInErp() {
        assertThat(TransactionViolationCode.exclusions())
                .contains(TransactionViolationCode.TRANSACTION_NOT_IN_ERP.name());
    }

    @Test
    void exclusions_shouldContainNetOffTx() {
        assertThat(TransactionViolationCode.exclusions())
                .contains(TransactionViolationCode.NET_OFF_TX.name());
    }

    @Test
    void exclusions_shouldContainAllTxItemsErased() {
        assertThat(TransactionViolationCode.exclusions())
                .contains(TransactionViolationCode.ALL_TX_ITEMS_ERASED.name());
    }

    @Test
    void exclusions_shouldContainDocumentMustBePresent() {
        assertThat(TransactionViolationCode.exclusions())
                .contains(TransactionViolationCode.DOCUMENT_MUST_BE_PRESENT.name());
    }

    @Test
    void exclusions_shouldContainAccountCodeCreditIsEmpty() {
        assertThat(TransactionViolationCode.exclusions())
                .contains(TransactionViolationCode.ACCOUNT_CODE_CREDIT_IS_EMPTY.name());
    }

    @Test
    void exclusions_shouldContainAccountCodeDebitIsEmpty() {
        assertThat(TransactionViolationCode.exclusions())
                .contains(TransactionViolationCode.ACCOUNT_CODE_DEBIT_IS_EMPTY.name());
    }

    @Test
    void exclusions_shouldContainExactlySixEntries() {
        assertThat(TransactionViolationCode.exclusions()).hasSize(6);
    }

    @Test
    void exclusions_returnsNewSetEachCall() {
        Set<String> first = TransactionViolationCode.exclusions();
        Set<String> second = TransactionViolationCode.exclusions();
        assertThat(first).isEqualTo(second).isNotSameAs(second);
    }
}
