package org.cardanofoundation.lob.app.organisation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationCreate;
import org.cardanofoundation.lob.app.organisation.domain.request.NetSuiteConfigurationUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.NetSuiteConfigurationStatusView;
import org.cardanofoundation.lob.app.organisation.repository.NetSuiteConfigStateRepository;
import org.cardanofoundation.lob.app.support.crypto.SHA3;
import org.cardanofoundation.lob.app.support.crypto.SecretCipher;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Admin-facing write path for per-organisation NetSuite configuration.
 * <p>
 * This module is NOT the owner of the configuration — the netsuite module is. Everything
 * persisted here is a projection used to answer the status endpoint; the private key is
 * encrypted, handed to the event, and immediately forgotten.
 * <p>
 * <b>Transaction boundary.</b> There is deliberately no {@code @Transactional} on this class.
 * A single {@code repository.save} runs in its own transaction (Spring Data's
 * {@code SimpleJpaRepository} is transactional), so it has committed by the time this class
 * publishes. That ordering matters: the Kafka bridge in cf-reeve-application listens with a
 * plain {@code @EventListener}, which fires synchronously inside {@code publishEvent} — so
 * publishing from inside a transaction would let the netsuite module store a configuration
 * whose projection row was later rolled back.
 */
@Service
@ConditionalOnProperty(name = "lob.organisation.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigAdminService {

    private final NetSuiteConfigStateRepository netSuiteConfigStateRepository;
    private final SecretCipher secretCipher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    public NetSuiteConfigurationStatusView status(String organisationId) {
        return netSuiteConfigStateRepository.findById(organisationId)
                .map(NetSuiteConfigurationStatusView::of)
                .orElseGet(NetSuiteConfigurationStatusView::notConfigured);
    }

    public Either<ProblemDetail, NetSuiteConfigurationStatusView> create(String organisationId,
                                                                        NetSuiteConfigurationCreate request,
                                                                        String user) {
        if (netSuiteConfigStateRepository.findById(organisationId).isPresent()) {
            return Either.left(alreadyExists(organisationId));
        }

        String encryptedKey = secretCipher.encrypt(request.getPrivateKey());
        String fingerprint = SHA3.digestAsHex(request.getPrivateKey());

        NetSuiteConfigState state = build(organisationId, request.getBaseUrl(), request.getTokenUrl(),
                request.getClientId(), request.getCertificateId(), fingerprint, user, Optional.empty());

        try {
            netSuiteConfigStateRepository.save(state);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent create for the same organisation.
            log.warn("Concurrent NetSuite configuration create for organisation {}", organisationId);

            return Either.left(alreadyExists(organisationId));
        }

        publish(state, encryptedKey, user);

        return Either.right(NetSuiteConfigurationStatusView.of(state));
    }

    public Either<ProblemDetail, NetSuiteConfigurationStatusView> update(String organisationId,
                                                                        NetSuiteConfigurationUpdate request,
                                                                        String user) {
        Optional<NetSuiteConfigState> existingM = netSuiteConfigStateRepository.findById(organisationId);
        if (existingM.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "No NetSuite configuration for organisation %s. Use POST to create one."
                            .formatted(organisationId));
            problem.setTitle("NETSUITE_CONFIGURATION_NOT_FOUND");

            return Either.left(problem);
        }
        NetSuiteConfigState existing = existingM.orElseThrow();

        boolean replacingKey = request.getPrivateKey() != null && !request.getPrivateKey().isBlank();
        String encryptedKey = replacingKey ? secretCipher.encrypt(request.getPrivateKey()) : null;
        String fingerprint = replacingKey
                ? SHA3.digestAsHex(request.getPrivateKey())
                : existing.getPrivateKeyFingerprint();

        Instant now = Instant.now(clock);

        // Mutate the loaded entity rather than building a fresh one: that keeps the @Version
        // populated so the save is a version-checked update. Building a detached entity with a
        // null version would make Spring Data treat it as new and attempt an insert.
        existing.setBaseUrl(request.getBaseUrl());
        existing.setTokenUrl(request.getTokenUrl());
        existing.setClientId(request.getClientId());
        existing.setCertificateId(request.getCertificateId());
        existing.setPrivateKeyFingerprint(fingerprint);
        existing.setSyncState(NetSuiteSyncState.PENDING);
        existing.setSyncMessage(null);
        existing.setRevision(existing.getRevision() + 1);
        existing.setNetsuiteValid(null);
        existing.setLastValidatedAt(null);
        existing.setValidationMessage(null);
        existing.setUpdatedAt(now);
        existing.setUpdatedBy(user);

        try {
            netSuiteConfigStateRepository.save(existing);
        } catch (OptimisticLockingFailureException e) {
            // Another replica updated this organisation concurrently and already claimed this
            // revision number. Publishing now would emit a second event under the same revision,
            // one of which the netsuite module would silently drop.
            log.warn("Concurrent NetSuite configuration update for organisation {}", organisationId);

            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The NetSuite configuration for organisation %s was modified concurrently. Reload and try again."
                            .formatted(organisationId));
            problem.setTitle("NETSUITE_CONFIGURATION_CONFLICT");

            return Either.left(problem);
        }

        publish(existing, encryptedKey, user);

        return Either.right(NetSuiteConfigurationStatusView.of(existing));
    }

    private NetSuiteConfigState build(String organisationId,
                                      String baseUrl,
                                      String tokenUrl,
                                      String clientId,
                                      String certificateId,
                                      String fingerprint,
                                      String user,
                                      Optional<NetSuiteConfigState> existing) {
        Instant now = Instant.now(clock);

        return NetSuiteConfigState.builder()
                .organisationId(organisationId)
                .baseUrl(baseUrl)
                .tokenUrl(tokenUrl)
                .clientId(clientId)
                .certificateId(certificateId)
                .privateKeyFingerprint(fingerprint)
                .syncState(NetSuiteSyncState.PENDING)
                .syncMessage(null)
                .revision(existing.map(s -> s.getRevision() + 1).orElse(1L))
                .netsuiteValid(null)
                .lastValidatedAt(null)
                .validationMessage(null)
                .createdAt(existing.map(NetSuiteConfigState::getCreatedAt).orElse(now))
                .updatedAt(now)
                .updatedBy(user)
                .build();
    }

    private void publish(NetSuiteConfigState state, String encryptedKey, String user) {
        NetSuiteConfigUpsertedEvent event = buildEvent(state, encryptedKey, user);

        // The Kafka bridge listens with a plain @EventListener, which fires synchronously inside
        // publishEvent. Normally there is no ambient transaction here (see the class javadoc), so
        // the projection has already committed. But if a future caller wraps this service in
        // @Transactional, publishing immediately would let the netsuite module store a
        // configuration whose projection row is then rolled back. Defer to after commit instead
        // of relying on call order.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(event);
                }
            });
            return;
        }

        doPublish(event);
    }

    private void doPublish(NetSuiteConfigUpsertedEvent event) {
        applicationEventPublisher.publishEvent(event);

        log.info("Published NetSuiteConfigUpsertedEvent for organisation {} revision {}",
                event.getOrganisationId(), event.getRevision());
    }

    private NetSuiteConfigUpsertedEvent buildEvent(NetSuiteConfigState state, String encryptedKey, String user) {
        return NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, user))
                .organisationId(state.getOrganisationId())
                .revision(state.getRevision())
                .baseUrl(state.getBaseUrl())
                .tokenUrl(state.getTokenUrl())
                .clientId(state.getClientId())
                .certificateId(state.getCertificateId())
                .privateKeyEncrypted(encryptedKey)
                .build();
    }

    private ProblemDetail alreadyExists(String organisationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "NetSuite configuration already exists for organisation %s. Use PUT to update it."
                        .formatted(organisationId));
        problem.setTitle("NETSUITE_CONFIGURATION_ALREADY_EXISTS");

        return problem;
    }

}
