package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Optional;

import lombok.val;

import org.springframework.context.ApplicationEventPublisher;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.ScheduledIngestionEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ScheduledReconcilationEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.assistance.AccountingPeriodCalculator;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class AccountingCoreServiceTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private TransactionBatchRepository transactionBatchRepository;

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @Mock
    private AccountingPeriodCalculator accountingPeriodCalculator;

    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private AccountingCoreService accountingCoreService;

    private UserExtractionParameters userExtractionParameters;

    @BeforeEach
    void setup() {
        userExtractionParameters = UserExtractionParameters.builder()
                .organisationId("org-123")
                .from(LocalDate.of(2023, 1, 1))
                .to(LocalDate.of(2023, 12, 31))
                .build();
    }

    @Test
    void scheduleIngestion_ShouldPublishEvent_WhenParametersAreValid() {
        // Given
        given(organisationPublicApi.findByOrganisationId(eq("org-123"))).willReturn(Optional.of(mock(Organisation.class)));
        given(accountingPeriodCalculator.calculateAccountingPeriod(any())).willReturn(Range.of(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31)));

        // When
        Either<Problem, Void> result = accountingCoreService.scheduleIngestion(userExtractionParameters);

        // Then
        assertThat(result.isRight()).isTrue();
        ArgumentCaptor<ScheduledIngestionEvent> eventCaptor = ArgumentCaptor.forClass(ScheduledIngestionEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());

        ScheduledIngestionEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.getOrganisationId()).isEqualTo("org-123");
        assertThat(publishedEvent.getUserExtractionParameters()).isEqualTo(userExtractionParameters);
    }


    @Test
    void scheduleIngestion_ShouldReturnError_WhenDateRangeIsInvalid() {
        // Given
        UserExtractionParameters invalidParameters = UserExtractionParameters.builder()
                .organisationId("org-123")
                .from(LocalDate.of(2023, 12, 31))
                .to(LocalDate.of(2023, 1, 1))
                .build();

        // When
        Either<Problem, Void> result = accountingCoreService.scheduleIngestion(invalidParameters);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(result.getLeft().getStatus()).isEqualTo(Status.BAD_REQUEST);
    }

    @Test
    void scheduleIngestion_ShouldReturnError_WhenDateRangeIsInvalidSinceTodayIsExcluded() {
        given(organisationPublicApi.findByOrganisationId(eq("org-123"))).willReturn(Optional.of(mock(Organisation.class)));

        given(accountingPeriodCalculator.calculateAccountingPeriod(any())).willReturn(Range.of(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 30)));

        // Given
        val invalidParameters = UserExtractionParameters.builder()
                .organisationId("org-123")
                .from(LocalDate.of(2023, 12, 1))
                .to(LocalDate.of(2023, 12, 31))
                .build();

        // When
        Either<Problem, Void> result = accountingCoreService.scheduleIngestion(invalidParameters);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("ORGANISATION_DATE_MISMATCH");
        assertThat(result.getLeft().getStatus()).isEqualTo(Status.BAD_REQUEST);
    }

    @Test
    void scheduleIngestion_ShouldReturnError_WhenDateRangeIsInvalidSinceOrganisationIsMissing() {
        given(organisationPublicApi.findByOrganisationId(eq("org-123"))).willReturn(Optional.empty());

        // Given
        val invalidParameters = UserExtractionParameters.builder()
                .organisationId("org-123")
                .from(LocalDate.of(2023, 12, 1))
                .to(LocalDate.of(2023, 12, 31))
                .build();

        // When
        Either<Problem, Void> result = accountingCoreService.scheduleIngestion(invalidParameters);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("ORGANISATION_NOT_FOUND");
        assertThat(result.getLeft().getStatus()).isEqualTo(Status.BAD_REQUEST);
    }

    @Test
    void scheduleReconcilation_ShouldPublishEvent_WhenParametersAreValid() {
        // Given
        String organisationId = "org-123";
        LocalDate fromDate = LocalDate.of(2023, 1, 1);
        LocalDate toDate = LocalDate.of(2023, 12, 31);

        given(organisationPublicApi.findByOrganisationId(eq(organisationId))).willReturn(Optional.of(mock(Organisation.class)));
        given(accountingPeriodCalculator.calculateAccountingPeriod(any())).willReturn(Range.of(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31)));
        // When
        Either<Problem, Void> result = accountingCoreService.scheduleReconcilation(organisationId, fromDate, toDate);

        // Then
        assertThat(result.isRight()).isTrue();
        ArgumentCaptor<ScheduledReconcilationEvent> eventCaptor = ArgumentCaptor.forClass(ScheduledReconcilationEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());

        ScheduledReconcilationEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.getOrganisationId()).isEqualTo(organisationId);
        assertThat(publishedEvent.getFrom()).isEqualTo(fromDate);
        assertThat(publishedEvent.getTo()).isEqualTo(toDate);
    }

    @Test
    void scheduleReconcilation_ShouldReturnError_WhenDateRangeIsInvalid() {
        // Given
        String organisationId = "org-123";
        LocalDate fromDate = LocalDate.of(2023, 12, 31);
        LocalDate toDate = LocalDate.of(2023, 1, 1);

        // When
        Either<Problem, Void> result = accountingCoreService.scheduleReconcilation(organisationId, fromDate, toDate);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(result.getLeft().getStatus()).isEqualTo(Status.BAD_REQUEST);
    }

}
