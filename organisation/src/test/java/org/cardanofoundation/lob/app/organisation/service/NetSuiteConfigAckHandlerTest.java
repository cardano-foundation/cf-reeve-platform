package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private void stubUpdated(int rows) {
        when(repository.applyAcknowledgement(anyString(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(rows);
    }

    @Test
    void marksAppliedAndValidWhenBothSucceeded() {
        stubUpdated(1);

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository).applyAcknowledgement(eq(ORG), eq(3L), eq(NetSuiteSyncState.APPLIED),
                eq(null), eq(Boolean.TRUE), eq(null), eq(NOW), eq(NOW));
    }

    @Test
    void marksAppliedButInvalidWhenCredentialsAreRejected() {
        stubUpdated(1);

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED, "401 Unauthorized"));

        verify(repository).applyAcknowledgement(eq(ORG), eq(3L), eq(NetSuiteSyncState.APPLIED),
                eq(null), eq(Boolean.FALSE), eq("401 Unauthorized"), eq(NOW), eq(NOW));
    }

    @Test
    void marksFailedWhenTheConfigurationCouldNotBeStored() {
        stubUpdated(1);

        handler.handleNetSuiteConfigApplied(
                ack(3L, NetSuiteConfigStatus.FAILED, null, "NETSUITE_CONFIGURATION_NOT_FOUND"));

        verify(repository).applyAcknowledgement(eq(ORG), eq(3L), eq(NetSuiteSyncState.FAILED),
                eq("NETSUITE_CONFIGURATION_NOT_FOUND"), eq(null), eq(null), eq(null), eq(NOW));
    }

    @Test
    void scopesTheWriteToTheAcknowledgedRevisionSoASupersededAckCannotRevertNewerConfiguration() {
        // Zero rows updated means the stored revision has moved on. The handler must treat that
        // as a no-op rather than writing anything.
        stubUpdated(0);

        handler.handleNetSuiteConfigApplied(
                ack(4L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository).applyAcknowledgement(eq(ORG), eq(4L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void toleratesAnAcknowledgementForAnUnknownOrganisation() {
        stubUpdated(0);

        handler.handleNetSuiteConfigApplied(
                ack(1L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null));

        verify(repository).applyAcknowledgement(eq(ORG), eq(1L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void isIdempotentSoRedeliveryOfTheSameAckIsHarmless() {
        stubUpdated(1);

        NetSuiteConfigAppliedEvent event =
                ack(3L, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null);

        handler.handleNetSuiteConfigApplied(event);
        handler.handleNetSuiteConfigApplied(event);

        ArgumentCaptor<Boolean> valid = ArgumentCaptor.forClass(Boolean.class);
        verify(repository, times(2)).applyAcknowledgement(eq(ORG), eq(3L), eq(NetSuiteSyncState.APPLIED),
                any(), valid.capture(), any(), any(), any());
        assertThat(valid.getAllValues()).containsExactly(Boolean.TRUE, Boolean.TRUE);
    }

}
