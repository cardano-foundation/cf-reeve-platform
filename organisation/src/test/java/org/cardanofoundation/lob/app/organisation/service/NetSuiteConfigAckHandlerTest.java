package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigAckHandlerTest {

    private static final String ORG = "org-1";
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    @Mock
    private NetSuiteConfigStateRepository repository;

    private NetSuiteConfigAckHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NetSuiteConfigAckHandler(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private NetSuiteConfigState pendingState(long revision) {
        return NetSuiteConfigState.builder()
                .organisationId(ORG)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert").privateKeyFingerprint("fp")
                .syncState(NetSuiteSyncState.PENDING)
                .revision(revision)
                .createdAt(NOW).updatedAt(NOW).updatedBy("admin")
                .build();
    }

    private NetSuiteConfigAppliedEvent ack(long revision,
                                           NetSuiteConfigStatus store,
                                           NetSuiteConfigStatus validation,
                                           String message) {
        return NetSuiteConfigAppliedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigAppliedEvent.VERSION))
                .organisationId(ORG)
                .revision(revision)
                .storeStatus(store)
                .validationStatus(validation)
                .message(message)
                .build();
    }

    @Test
    void marksAppliedAndValidWhenBothSucceeded() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.APPLIED);
        assertThat(saved.getValue().getNetsuiteValid()).isTrue();
        assertThat(saved.getValue().getLastValidatedAt()).isEqualTo(NOW);
    }

    @Test
    void marksAppliedButInvalidWhenCredentialsAreRejected() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED, "401 Unauthorized"));

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.APPLIED);
        assertThat(saved.getValue().getNetsuiteValid()).isFalse();
        assertThat(saved.getValue().getValidationMessage()).isEqualTo("401 Unauthorized");
    }

    @Test
    void marksFailedWhenTheConfigurationCouldNotBeStored() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.FAILED, null, "NETSUITE_CONFIGURATION_NOT_FOUND"));

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSyncState()).isEqualTo(NetSuiteSyncState.FAILED);
        assertThat(saved.getValue().getSyncMessage()).isEqualTo("NETSUITE_CONFIGURATION_NOT_FOUND");
        assertThat(saved.getValue().getNetsuiteValid()).isNull();
    }

    @Test
    void ignoresAnAcknowledgementForAnOlderRevision() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(5L)));

        handler.handleNetSuiteConfigApplied(
                ack(4L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository, never()).save(any());
    }

    @Test
    void ignoresAnAcknowledgementForAnUnknownOrganisation() {
        when(repository.findById(ORG)).thenReturn(Optional.empty());

        handler.handleNetSuiteConfigApplied(
                ack(1L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository, never()).save(any());
    }

    @Test
    void isIdempotentSoRedeliveryOfTheSameAckIsHarmless() {
        when(repository.findById(ORG)).thenReturn(Optional.of(pendingState(3L)));

        NetSuiteConfigAppliedEvent event =
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null);

        handler.handleNetSuiteConfigApplied(event);
        handler.handleNetSuiteConfigApplied(event);

        ArgumentCaptor<NetSuiteConfigState> saved = ArgumentCaptor.forClass(NetSuiteConfigState.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(state -> {
            assertThat(state.getSyncState()).isEqualTo(NetSuiteSyncState.APPLIED);
            assertThat(state.getNetsuiteValid()).isTrue();
        });
    }

}
