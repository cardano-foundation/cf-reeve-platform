package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.NetSuiteConfigurationStatusView;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigAdminServiceTest {

    private static final String ORG = "org-1";
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQ==\n-----END PRIVATE KEY-----";

    @Mock
    private NetSuiteConfigStateRepository repository;
    @Mock
    private SecretCipher secretCipher;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private NetSuiteConfigAdminService service;

    @BeforeEach
    void setUp() {
        service = new NetSuiteConfigAdminService(repository, secretCipher, eventPublisher,
                Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC));
    }

    private NetSuiteConfigurationCreate createRequest() {
        return new NetSuiteConfigurationCreate("https://base", "https://token", "client", "cert", PEM);
    }

    private NetSuiteConfigState existingState() {
        return NetSuiteConfigState.builder()
                .organisationId(ORG)
                .baseUrl("https://old")
                .tokenUrl("https://oldtoken")
                .clientId("oldclient")
                .certificateId("oldcert")
                .privateKeyFingerprint("oldfp")
                .syncState(NetSuiteSyncState.APPLIED)
                .revision(4L)
                .netsuiteValid(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedBy("someone")
                .build();
    }

    @Test
    void createPersistsPendingStateAndPublishesTheEvent() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(PEM)).thenReturn("v1:ENVELOPE");

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result =
                service.create(ORG, createRequest(), "admin");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getSyncState()).isEqualTo("PENDING");
        assertThat(result.get().getNetsuiteValid()).isNull();

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRevision()).isEqualTo(1L);
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.PENDING);

        ArgumentCaptor<NetSuiteConfigUpsertedEvent> published =
                ArgumentCaptor.forClass(NetSuiteConfigUpsertedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getPrivateKeyEncrypted()).isEqualTo("v1:ENVELOPE");
        assertThat(published.getValue().getRevision()).isEqualTo(1L);
    }

    @Test
    void createNeverStoresTheSecretInTheProjection() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(PEM)).thenReturn("v1:ENVELOPE");

        service.create(ORG, createRequest(), "admin");

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyFingerprint())
                .isNotBlank()
                .isNotEqualTo(PEM)
                .isNotEqualTo("v1:ENVELOPE");
    }

    @Test
    void createRejectsAnOrganisationThatAlreadyHasARow() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result =
                service.create(ORG, createRequest(), "admin");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_ALREADY_EXISTS");
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(NetSuiteConfigUpsertedEvent.class));
    }

    @Test
    void createLosingAConcurrentRaceReportsConflictAndPublishesNothing() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        when(secretCipher.encrypt(PEM)).thenReturn("v1:ENVELOPE");
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result =
                service.create(ORG, createRequest(), "admin");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_ALREADY_EXISTS");
        verify(eventPublisher, never()).publishEvent(any(NetSuiteConfigUpsertedEvent.class));
    }

    @Test
    void updateRejectsAnOrganisationWithNoRow() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result = service.update(ORG,
                new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", null), "admin");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("NETSUITE_CONFIGURATION_NOT_FOUND");
        assertThat(result.getLeft().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        verify(eventPublisher, never()).publishEvent(any(NetSuiteConfigUpsertedEvent.class));
    }

    @Test
    void updateWithoutAKeyPublishesNullSoTheNetsuiteModuleReusesTheStoredOne() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));

        Either<ProblemDetail, NetSuiteConfigurationStatusView> result = service.update(ORG,
                new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", "  "), "admin");

        assertThat(result.isRight()).isTrue();

        ArgumentCaptor<NetSuiteConfigUpsertedEvent> published =
                ArgumentCaptor.forClass(NetSuiteConfigUpsertedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getPrivateKeyEncrypted()).isNull();
        assertThat(published.getValue().getRevision()).isEqualTo(5L);

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyFingerprint()).isEqualTo("oldfp");
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.PENDING);
        assertThat(saved.getValue().getNetsuiteValid()).isNull();
    }

    @Test
    void updateWithANewKeyEncryptsItAndRefreshesTheFingerprint() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));
        when(secretCipher.encrypt(PEM)).thenReturn("v1:NEWENVELOPE");

        service.update(ORG,
                new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", PEM), "admin");

        ArgumentCaptor<NetSuiteConfigUpsertedEvent> published =
                ArgumentCaptor.forClass(NetSuiteConfigUpsertedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().getPrivateKeyEncrypted()).isEqualTo("v1:NEWENVELOPE");

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPrivateKeyFingerprint()).isNotEqualTo("oldfp");
    }

    @Test
    void updatePreservesTheOriginalCreationTimestamp() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));

        service.update(ORG,
                new NetSuiteConfigurationUpdate("https://base", "https://token", "client", "cert", null), "admin");

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(Instant.parse("2026-08-14T10:00:00Z"));
    }

    @Test
    void statusReportsNotConfiguredRatherThanFailingForAFreshOrganisation() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        NetSuiteConfigurationStatusView view = service.status(ORG);

        assertThat(view.isConfigured()).isFalse();
        assertThat(view.getBaseUrl()).isNull();
    }

    @Test
    void statusNeverExposesTheKeyOnlyItsFingerprint() {
        when(repository.findById(ORG)).thenReturn(Optional.of(existingState()));

        NetSuiteConfigurationStatusView view = service.status(ORG);

        assertThat(view.isConfigured()).isTrue();
        assertThat(view.getPrivateKeyFingerprint()).isEqualTo("oldfp");
        assertThat(view.getNetsuiteValid()).isTrue();
    }

}
