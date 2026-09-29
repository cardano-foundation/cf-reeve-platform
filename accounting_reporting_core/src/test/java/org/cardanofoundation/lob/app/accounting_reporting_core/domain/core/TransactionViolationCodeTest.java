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
    void exclusions_shouldNotContainDocumentMustBePresent() {
        assertThat(TransactionViolationCode.exclusions())
                .doesNotContain(TransactionViolationCode.DOCUMENT_MUST_BE_PRESENT.name());
    }

    @Test
    void exclusions_shouldNotContainAccountCodeCreditIsEmpty() {
        assertThat(TransactionViolationCode.exclusions())
                .doesNotContain(TransactionViolationCode.ACCOUNT_CODE_CREDIT_IS_EMPTY.name());
    }

    @Test
    void exclusions_shouldNotContainAccountCodeDebitIsEmpty() {
        assertThat(TransactionViolationCode.exclusions())
                .doesNotContain(TransactionViolationCode.ACCOUNT_CODE_DEBIT_IS_EMPTY.name());
    }

    @Test
    void exclusions_shouldContainExactlyThreeEntries() {
        assertThat(TransactionViolationCode.exclusions()).hasSize(3);
    }

    @Test
    void exclusions_shouldContainOnlyExpectedCodes() {
        assertThat(TransactionViolationCode.exclusions())
                .containsExactlyInAnyOrder(
                        TransactionViolationCode.NET_OFF_TX.name(),
                        TransactionViolationCode.TRANSACTION_NOT_IN_ERP.name(),
                        TransactionViolationCode.ALL_TX_ITEMS_ERASED.name());
    }

    @Test
    void exclusions_shouldNotContainAnyOtherViolationCode() {
        Set<String> excludedFromExclusions = java.util.Arrays.stream(TransactionViolationCode.values())
                .map(Enum::name)
                .filter(name -> !Set.of(
                        TransactionViolationCode.NET_OFF_TX.name(),
                        TransactionViolationCode.TRANSACTION_NOT_IN_ERP.name(),
                        TransactionViolationCode.ALL_TX_ITEMS_ERASED.name())
                        .contains(name))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(TransactionViolationCode.exclusions())
                .doesNotContainAnyElementsOf(excludedFromExclusions);
    }

    @Test
    void exclusions_returnsNewSetEachCall() {
        Set<String> first = TransactionViolationCode.exclusions();
        Set<String> second = TransactionViolationCode.exclusions();
        assertThat(first).isEqualTo(second).isNotSameAs(second);
    }

    @Test
    void exclusions_returnedSetIsMutable() {
        Set<String> exclusion = TransactionViolationCode.exclusions();
        exclusion.add("SOME_OTHER_CODE");
        assertThat(exclusion).contains("SOME_OTHER_CODE");
    }
}
