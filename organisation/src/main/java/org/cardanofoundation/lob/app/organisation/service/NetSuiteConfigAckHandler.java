package org.cardanofoundation.lob.app.organisation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;

/**
 * Writes the netsuite module's verdict onto the projection.
 * <p>
 * Runs on whichever organisation pod is assigned the partition — not necessarily the one that
 * served the original HTTP request, and possibly none of them if the request pod has since been
 * replaced. That is the point: the verdict is durable state, not an in-memory future.
 * <p>
 * Idempotent, so it is safe under at-least-once delivery and safe to run both in-process (merged
 * deployment) and again from Kafka.
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
        Optional<NetSuiteConfigState> stateM =
                netSuiteConfigStateRepository.findById(event.getOrganisationId());

        if (stateM.isEmpty()) {
            log.warn("Ignoring NetSuiteConfigAppliedEvent for unknown organisation {}",
                    event.getOrganisationId());
            return;
        }

        NetSuiteConfigState state = stateM.orElseThrow();

        if (event.getRevision() < state.getRevision()) {
            log.info("Ignoring stale NetSuiteConfigAppliedEvent for organisation {}: ack revision {} < current {}",
                    event.getOrganisationId(), event.getRevision(), state.getRevision());
            return;
        }

        boolean stored = event.getStoreStatus() == NetSuiteConfigStatus.SUCCESS;
        Instant now = Instant.now(clock);

        state.setSyncState(stored ? NetSuiteSyncState.APPLIED : NetSuiteSyncState.FAILED);
        state.setSyncMessage(stored ? null : event.getMessage());

        if (stored && event.getValidationStatus() != null) {
            boolean valid = event.getValidationStatus() == NetSuiteConfigStatus.SUCCESS;

            state.setNetsuiteValid(valid);
            state.setValidationMessage(valid ? null : event.getMessage());
            state.setLastValidatedAt(now);
        }

        state.setUpdatedAt(now);

        try {
            netSuiteConfigStateRepository.save(state);
        } catch (OptimisticLockingFailureException e) {
            // An admin update committed a newer revision while this acknowledgement was being
            // processed. Hibernate writes every column, so persisting this stale snapshot would
            // roll the projection back to the previous configuration. Drop it: the newer
            // revision has its own acknowledgement in flight.
            log.info("Discarding NetSuiteConfigAppliedEvent for organisation {} revision {} — superseded by a concurrent update",
                    event.getOrganisationId(), event.getRevision());
            return;
        }

        log.info("Applied NetSuiteConfigAppliedEvent for organisation {}: syncState={}, netsuiteValid={}",
                event.getOrganisationId(), state.getSyncState(), state.getNetsuiteValid());
    }

}
