package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.NetSuiteConfigRepository;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Owns the per-organisation NetSuite configuration.
 * <p>
 * Storing happens before verification: storing is the durable act, verification is a report on
 * it. A configuration whose credentials NetSuite rejects is still stored, and the acknowledgement
 * carries {@code validationStatus = FAILED} so the organisation projection can show it.
 * <p>
 * <b>No {@code @Transactional} on {@link #apply}.</b> Verification makes an outbound HTTP call to
 * NetSuite, which must not happen while a database connection is held — a slow or hanging NetSuite
 * would otherwise pin connections from the pool for the duration. Each repository call carries its
 * own transaction, which is sufficient here because Kafka keys configuration events by
 * organisationId, so events for one organisation are processed in order by a single consumer.
 */
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigService {

    /** Upstream error text is attacker-influenced and unbounded; cap what we persist and echo. */
    private static final int MAX_VALIDATION_MESSAGE_LENGTH = 500;

    private final NetSuiteConfigRepository netSuiteConfigRepository;
    private final NetSuiteClientRegistry netSuiteClientRegistry;
    private final Clock clock;

    public NetSuiteConfigAppliedEvent apply(NetSuiteConfigUpsertedEvent event) {
        String organisationId = event.getOrganisationId();
        Optional<NetSuiteConfigEntity> existingM = netSuiteConfigRepository.findById(organisationId);

        if (existingM.isPresent() && event.getRevision() <= existingM.orElseThrow().getRevision()) {
            NetSuiteConfigEntity existing = existingM.orElseThrow();
            NetSuiteConfigStatus storedVerdict = storedValidationStatus(existing);

            if (storedVerdict == null) {
                // The configuration was stored but the verdict never was — the process died
                // between the two writes. Replaying a null verdict here would leave the projection
                // APPLIED with validity permanently unknown, so verify now instead.
                log.info("No recorded verdict for organisation {} revision {}; verifying on replay",
                        organisationId, existing.getRevision());

                return verify(organisationId, existing.getRevision());
            }

            log.info("Replaying stored verdict for already-applied NetSuiteConfigUpsertedEvent, organisation {} revision {}",
                    organisationId, event.getRevision());

            // Replay the verdict actually recorded for this configuration. Returning a synthetic
            // SUCCESS here would let a redelivered event flip rejected credentials to valid.
            return ack(organisationId, existing.getRevision(),
                    NetSuiteConfigStatus.SUCCESS,
                    storedVerdict,
                    existing.getValidationMessage());
        }

        String encryptedKey = event.getPrivateKeyEncrypted();
        if (encryptedKey == null) {
            if (existingM.isEmpty()) {
                log.error("NetSuiteConfigUpsertedEvent for organisation {} carries no key and none is stored",
                        organisationId);

                return ack(organisationId, event.getRevision(), NetSuiteConfigStatus.FAILED, null,
                        "NETSUITE_CONFIGURATION_NOT_FOUND: no private key supplied and none stored for this organisation");
            }
            encryptedKey = existingM.orElseThrow().getPrivateKeyEncrypted();
        }

        Instant now = Instant.now(clock);

        netSuiteConfigRepository.save(NetSuiteConfigEntity.builder()
                .organisationId(organisationId)
                .baseUrl(event.getBaseUrl())
                .tokenUrl(event.getTokenUrl())
                .clientId(event.getClientId())
                .certificateId(event.getCertificateId())
                .privateKeyEncrypted(encryptedKey)
                .revision(event.getRevision())
                .validationStatus(null)
                .validationMessage(null)
                .createdAt(existingM.map(NetSuiteConfigEntity::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build());

        netSuiteClientRegistry.evict(organisationId);

        return verify(organisationId, event.getRevision());
    }

    /**
     * Runs outside any transaction — see the class javadoc. Records the verdict so a later replay
     * can be answered without calling NetSuite again.
     */
    private NetSuiteConfigAppliedEvent verify(String organisationId, long revision) {
        Either<ProblemDetail, NetSuiteClient> clientE = netSuiteClientRegistry.forOrganisation(organisationId);
        if (clientE.isLeft()) {
            return recordAndAck(organisationId, revision, NetSuiteConfigStatus.FAILED, clientE.getLeft().getDetail());
        }

        Either<ProblemDetail, Void> connection = clientE.get().testConnection();
        if (connection.isLeft()) {
            log.warn("NetSuite credentials for organisation {} were stored but rejected", organisationId);

            return recordAndAck(organisationId, revision, NetSuiteConfigStatus.FAILED, connection.getLeft().getDetail());
        }

        return recordAndAck(organisationId, revision, NetSuiteConfigStatus.SUCCESS, null);
    }

    private NetSuiteConfigAppliedEvent recordAndAck(String organisationId,
                                                    long revision,
                                                    NetSuiteConfigStatus validationStatus,
                                                    String rawMessage) {
        String message = truncate(rawMessage);

        // A conditional update, not read-mutate-save: the verdict is written after an outbound
        // HTTP call, so a newer revision may have committed meanwhile. A full-row merge would
        // restore this invocation's stale URL, client id and key over the newer configuration.
        int updated = netSuiteConfigRepository.recordValidationVerdict(
                organisationId, revision, validationStatus.name(), message);

        if (updated == 0) {
            log.info("Discarding verdict for organisation {} revision {} — superseded by a newer configuration",
                    organisationId, revision);
        }

        return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, validationStatus, message);
    }

    private NetSuiteConfigStatus storedValidationStatus(NetSuiteConfigEntity entity) {
        if (entity.getValidationStatus() == null) {
            return null;
        }

        try {
            return NetSuiteConfigStatus.valueOf(entity.getValidationStatus());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised stored validation status '{}' for organisation {}",
                    entity.getValidationStatus(), entity.getOrganisationId());

            return null;
        }
    }

    /**
     * NetSuite error bodies are unbounded and can carry upstream/proxy content. Cap the length
     * before it is logged, persisted and returned by the status endpoint.
     */
    private String truncate(String message) {
        if (message == null) {
            return null;
        }

        return message.length() <= MAX_VALIDATION_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_VALIDATION_MESSAGE_LENGTH) + "…";
    }

    private NetSuiteConfigAppliedEvent ack(String organisationId,
                                           long revision,
                                           NetSuiteConfigStatus storeStatus,
                                           NetSuiteConfigStatus validationStatus,
                                           String message) {
        return NetSuiteConfigAppliedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigAppliedEvent.VERSION))
                .organisationId(organisationId)
                .revision(revision)
                .storeStatus(storeStatus)
                .validationStatus(validationStatus)
                .message(message)
                .build();
    }

}
