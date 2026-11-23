package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class ProjectConvertorTest {

    private ProjectConvertor projectConvertor;

    @BeforeEach
    void setUp() {
        projectConvertor = new ProjectConvertor();
    }

    @Test
    void shouldReturnRightWhenProjectCodeIsValid() {
        // Given
        String validProjectCode = "AN 000001 2023 Summit";

        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(validProjectCode);

        // Then
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("AN 000001 2023");
    }

    @Test
    void shouldReturnRightWhenAnotherValidProjectCode() {
        // Given
        String string = "CF 000002 2023 Ledger Code";

        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(string);

        // Then
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("CF 000002 2023");
    }

    @Test
    void shouldReturnLeftWhenProjectCodeIsInvalid() {
        // Given
        String invalidProjectCode = "Invalid Code";

        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(invalidProjectCode);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_PROJECT");
        assertThat(result.getLeft().getDetail()).isEqualTo("Invalid project code");
        assertThat(result.getLeft().getProperties().get("project")).isEqualTo(invalidProjectCode);
    }

    @Test
    void shouldReturnLeftWhenProjectCodeHasInvalidFormat() {
        // Given
        String invalidProjectCode = "AN1234562023Summit";

        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(invalidProjectCode);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_PROJECT");
        assertThat(result.getLeft().getDetail()).isEqualTo("Invalid project code");
        assertThat(result.getLeft().getProperties().get("project")).isEqualTo(invalidProjectCode);
    }

    @Test
    void shouldReturnLeftWhenProjectCodeHasIncorrectYear() {
        // Given
        String invalidProjectCode = "AN 000001 999 Summit";

        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(invalidProjectCode);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_PROJECT");
        assertThat(result.getLeft().getDetail()).isEqualTo("Invalid project code");
        assertThat(result.getLeft().getProperties().get("project")).isEqualTo(invalidProjectCode);
    }

    @Test
    void shouldReturnLeftWhenProjectCodeIsMissingLastWord() {
        // Given
        String invalidProjectCode = "AN 000001 2023";

        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(invalidProjectCode);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_PROJECT");
        assertThat(result.getLeft().getDetail()).isEqualTo("Invalid project code");
        assertThat(result.getLeft().getProperties().get("project")).isEqualTo(invalidProjectCode);
    }

}
