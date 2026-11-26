package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountCreditConvertorTest {

    private final AccountCreditConvertor convertor = new AccountCreditConvertor();

    @Test
    void returnsAccountNameWithoutLeadingNumber() {
        Either<ProblemDetail, String> result = convertor.apply("2101110100 Accounts Payable : Accounts Payable CHF");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("Accounts Payable : Accounts Payable CHF");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Accounts Payable", "2101110100", "ABC123 Accounts Payable"})
    void rejectsValueWithoutNumberAndName(String input) {
        Either<ProblemDetail, String> result = convertor.apply(input);

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("INVALID_CREDIT_ACCOUNT");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("project", input);
    }

}
