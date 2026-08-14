package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigServiceTest {

    private static final String ORG = "org-1";

    @Mock
    private NetSuiteConfigRepository repository;
    @Mock
    private NetSuiteClientRegistry registry;

    private NetSuiteConfigService service;

    @BeforeEach
    void setUp() {
        service = new NetSuiteConfigService(repository, registry,
                Clock.fixed(Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC));
    }

    private NetSuiteConfigUpsertedEvent event(long revision, String encryptedKey) {
        return NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId(ORG)
                .revision(revision)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted(encryptedKey)
                .build();
    }

    private NetSuiteConfigEntity stored(long revision) {
        return NetSuiteConfigEntity.builder()
                .organisationId(ORG)
                .baseUrl("https://old").tokenUrl("https://oldtoken")
                .clientId("oldclient").certificateId("oldcert")
                .privateKeyEncrypted("v1:OLDENVELOPE")
                .revision(revision)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();
    }

    private void stubSuccessfulConnection() {
        NetSuiteClient client = mock(NetSuiteClient.class);
        when(client.testConnection()).thenReturn(Either.right(null));
        when(registry.forOrganisation(ORG)).thenReturn(Either.right(client));
    }

    @Test
    void storesANewConfigurationAndReportsBothSuccesses() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        stubSuccessfulConnection();

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getValidationStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getRevision()).isEqualTo(1L);

        ArgumentCaptor<NetSuiteConfigEntity> saved = ArgumentCaptor.forClass(NetSuiteConfigEntity.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getPrivateKeyEncrypted()).isEqualTo("v1:ENVELOPE");
    }

    @Test
    void evictsTheCachedClientSoTheNewCredentialsTakeEffect() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        stubSuccessfulConnection();

        service.apply(event(1L, "v1:ENVELOPE"));

        verify(registry).evict(ORG);
    }

    @Test
    void keepsTheStoredKeyWhenTheEventCarriesNone() {
        when(repository.findById(ORG)).thenReturn(Optional.of(stored(1L)));
        stubSuccessfulConnection();

        NetSuiteConfigAppliedEvent ack = service.apply(event(2L, null));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);

        ArgumentCaptor<NetSuiteConfigEntity> saved = ArgumentCaptor.forClass(NetSuiteConfigEntity.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getPrivateKeyEncrypted()).isEqualTo("v1:OLDENVELOPE");
        assertThat(saved.getAllValues().get(0).getBaseUrl()).isEqualTo("https://base");
    }

    @Test
    void failsWhenNoKeyIsSuppliedAndNoneIsStored() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, null));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.FAILED);
        assertThat(ack.getValidationStatus()).isNull();
        assertThat(ack.getMessage()).contains("NETSUITE_CONFIGURATION_NOT_FOUND");
        verify(repository, never()).save(any());
    }

    @Test
    void storesTheConfigurationEvenWhenVerificationFails() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());
        NetSuiteClient client = mock(NetSuiteClient.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid credentials");
        when(client.testConnection()).thenReturn(Either.left(problem));
        when(registry.forOrganisation(ORG)).thenReturn(Either.right(client));

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getValidationStatus()).isEqualTo(NetSuiteConfigStatus.FAILED);
        assertThat(ack.getMessage()).contains("invalid credentials");
        verify(repository, atLeastOnce()).save(any());
    }

    @Test
    void replayOfTheSameRevisionRepeatsTheStoredVerdictInsteadOfFabricatingSuccess() {
        NetSuiteConfigEntity rejected = stored(5L);
        rejected.setValidationStatus(NetSuiteConfigStatus.FAILED.name());
        rejected.setValidationMessage("401 Unauthorized");
        when(repository.findById(ORG)).thenReturn(Optional.of(rejected));

        NetSuiteConfigAppliedEvent ack = service.apply(event(5L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        // Without this, a Kafka redelivery would flip rejected credentials to valid.
        assertThat(ack.getValidationStatus()).isEqualTo(NetSuiteConfigStatus.FAILED);
        assertThat(ack.getMessage()).isEqualTo("401 Unauthorized");
        verify(repository, never()).save(any());
    }

    @Test
    void replayOfTheSameRevisionRepeatsAStoredSuccess() {
        NetSuiteConfigEntity accepted = stored(5L);
        accepted.setValidationStatus(NetSuiteConfigStatus.SUCCESS.name());
        when(repository.findById(ORG)).thenReturn(Optional.of(accepted));

        NetSuiteConfigAppliedEvent ack = service.apply(event(5L, "v1:ENVELOPE"));

        assertThat(ack.getValidationStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        verify(repository, never()).save(any());
    }

    @Test
    void ignoresAnOutOfOrderOlderRevisionAndReportsTheCurrentRevision() {
        NetSuiteConfigEntity current = stored(5L);
        current.setValidationStatus(NetSuiteConfigStatus.SUCCESS.name());
        when(repository.findById(ORG)).thenReturn(Optional.of(current));

        NetSuiteConfigAppliedEvent ack = service.apply(event(3L, "v1:ENVELOPE"));

        assertThat(ack.getStoreStatus()).isEqualTo(NetSuiteConfigStatus.SUCCESS);
        assertThat(ack.getRevision()).isEqualTo(5L);
        verify(repository, never()).save(any());
    }

    @Test
    void recordsTheValidationVerdictSoItSurvivesARedelivery() {
        when(repository.findById(ORG))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored(1L)));
        NetSuiteClient client = mock(NetSuiteClient.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid credentials");
        when(client.testConnection()).thenReturn(Either.left(problem));
        when(registry.forOrganisation(ORG)).thenReturn(Either.right(client));

        service.apply(event(1L, "v1:ENVELOPE"));

        ArgumentCaptor<NetSuiteConfigEntity> saved = ArgumentCaptor.forClass(NetSuiteConfigEntity.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getValidationStatus()).isEqualTo(NetSuiteConfigStatus.FAILED.name());
        assertThat(saved.getAllValues().get(1).getValidationMessage()).isEqualTo("invalid credentials");
    }

    @Test
    void boundsAnUnreasonablyLongUpstreamErrorBeforeStoringOrReturningIt() {
        when(repository.findById(ORG))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored(1L)));
        NetSuiteClient client = mock(NetSuiteClient.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "x".repeat(5000));
        when(client.testConnection()).thenReturn(Either.left(problem));
        when(registry.forOrganisation(ORG)).thenReturn(Either.right(client));

        NetSuiteConfigAppliedEvent ack = service.apply(event(1L, "v1:ENVELOPE"));

        assertThat(ack.getMessage()).hasSizeLessThan(600);
    }

    @Test
    void preservesTheOriginalCreationTimestampOnUpdate() {
        when(repository.findById(ORG)).thenReturn(Optional.of(stored(1L)));
        stubSuccessfulConnection();

        service.apply(event(2L, "v1:NEW"));

        ArgumentCaptor<NetSuiteConfigEntity> saved = ArgumentCaptor.forClass(NetSuiteConfigEntity.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(saved.getAllValues().get(0).getUpdatedAt()).isEqualTo(Instant.parse("2026-08-14T10:00:00Z"));
    }

}
