package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigService {

    private final NetSuiteConfigRepository netSuiteConfigRepository;
    private final NetSuiteClientRegistry netSuiteClientRegistry;
    private final Clock clock;

    @Transactional
    public NetSuiteConfigAppliedEvent apply(NetSuiteConfigUpsertedEvent event) {
        String organisationId = event.getOrganisationId();
        Optional<NetSuiteConfigEntity> existingM = netSuiteConfigRepository.findById(organisationId);

        if (existingM.isPresent() && event.getRevision() <= existingM.orElseThrow().getRevision()) {
            log.info("Ignoring already-applied NetSuiteConfigUpsertedEvent for organisation {} revision {}",
                    organisationId, event.getRevision());

            return ack(organisationId, event.getRevision(), NetSuiteConfigStatus.SUCCESS,
                    NetSuiteConfigStatus.SUCCESS, null);
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
                .createdAt(existingM.map(NetSuiteConfigEntity::getCreatedAt).orElse(now))
                .updatedAt(now)
                .build());

        netSuiteClientRegistry.evict(organisationId);

        return verify(organisationId, event.getRevision());
    }

    private NetSuiteConfigAppliedEvent verify(String organisationId, long revision) {
        Either<ProblemDetail, NetSuiteClient> clientE = netSuiteClientRegistry.forOrganisation(organisationId);
        if (clientE.isLeft()) {
            return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED,
                    clientE.getLeft().getDetail());
        }

        Either<ProblemDetail, Void> connection = clientE.get().testConnection();
        if (connection.isLeft()) {
            log.warn("NetSuite credentials for organisation {} were stored but rejected: {}",
                    organisationId, connection.getLeft().getDetail());

            return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.FAILED,
                    connection.getLeft().getDetail());
        }

        return ack(organisationId, revision, NetSuiteConfigStatus.SUCCESS, NetSuiteConfigStatus.SUCCESS, null);
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
