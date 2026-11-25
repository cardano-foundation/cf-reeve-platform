package org.cardanofoundation.lob.app.cf_netsuite_altavia_erp_connector.convertors;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


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

    @ParameterizedTest
    @ValueSource(strings = {
            "Invalid Code",          // not a project code at all
            "AN1234562023Summit",    // no separators
            "AN 000001 999 Summit",  // year has three digits
            "AN 000001 2023"         // missing project name
    })
    void shouldReturnLeftWhenProjectCodeIsInvalid(String invalidProjectCode) {
        // When
        Either<ProblemDetail, String> result = projectConvertor.apply(invalidProjectCode);

        // Then
        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getTitle()).isEqualTo("INVALID_PROJECT");
        assertThat(problem.getDetail()).isEqualTo("Invalid project code");
        assertThat(problem.getProperties()).containsEntry("project", invalidProjectCode);
    }

}
