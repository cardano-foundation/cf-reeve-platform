package org.cardanofoundation.lob.app.organisation.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;

/**
 * Writes the netsuite module's verdict onto the projection.
 * <p>
 * Runs on whichever organisation pod is assigned the partition — not necessarily the one that
 * served the original HTTP request, and possibly none of them if that pod has since been replaced.
 * That is the point of D7: the verdict is durable state, not an in-memory future.
 * <p>
 * The write is a conditional update scoped to the acknowledged revision, so it is idempotent under
 * at-least-once delivery and a no-op once a newer configuration has superseded it.
 */
@Service
@ConditionalOnProperty(name = "lob.organisation.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigAckHandler {

    private final NetSuiteConfigStateRepository netSuiteConfigStateRepository;
    private final Clock clock;

    @EventListener
    @Transactional
    public void handleNetSuiteConfigApplied(NetSuiteConfigAppliedEvent event) {
        boolean stored = event.getStoreStatus() == NetSuiteConfigStatus.SUCCESS;
        Instant now = Instant.now(clock);

        Boolean netsuiteValid = null;
        Instant lastValidatedAt = null;
        String validationMessage = null;

        if (stored && event.getValidationStatus() != null) {
            boolean valid = event.getValidationStatus() == NetSuiteConfigStatus.SUCCESS;

            netsuiteValid = valid;
            validationMessage = valid ? null : event.getMessage();
            lastValidatedAt = now;
        }

        int updated = netSuiteConfigStateRepository.applyAcknowledgement(
                event.getOrganisationId(),
                event.getRevision(),
                stored ? NetSuiteSyncState.APPLIED : NetSuiteSyncState.FAILED,
                stored ? null : event.getMessage(),
                netsuiteValid,
                validationMessage,
                lastValidatedAt,
                now);

        if (updated == 0) {
            // Either the organisation is unknown here, or an admin has already committed a newer
            // revision. Both are benign: the newer revision has its own acknowledgement in flight.
            log.info("Ignoring NetSuiteConfigAppliedEvent for organisation {} revision {} — unknown or superseded",
                    event.getOrganisationId(), event.getRevision());
            return;
        }

        log.info("Applied NetSuiteConfigAppliedEvent for organisation {} revision {}: syncState={}, netsuiteValid={}",
                event.getOrganisationId(), event.getRevision(),
                stored ? NetSuiteSyncState.APPLIED : NetSuiteSyncState.FAILED, netsuiteValid);
    }

}
