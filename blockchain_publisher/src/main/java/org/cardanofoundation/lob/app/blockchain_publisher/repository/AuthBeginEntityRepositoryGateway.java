package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.notFinalisedButVisibleOnChain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.authbegin.AuthBeginEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.DispatchingStrategy;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AuthBeginEntityRepositoryGateway {

    private final AuthBeginEntityRepository authBeginEntityRepository;
    private final Clock clock;

    @Value("${lob.blockchain_publisher.dispatcher.lock_timeout:PT3H}")
    private Duration lockTimeoutDuration;

    /**
     * Atomically claims AUTH_BEGIN ceremonies ready for dispatch: the locking read (FOR UPDATE SKIP LOCKED) and the
     * lockedAt write commit together, before this method returns (REQUIRES_NEW), so a concurrent dispatcher
     * instance can never claim the same rows. Returns the claimed ids in dispatch order.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<String> claimAuthBeginsReadyToBeDispatched(String organisationId,
            int pullBatchSize,
            DispatchingStrategy<AuthBeginEntity> dispatchingStrategy) {
        Set<AuthBeginEntity> free = authBeginEntityRepository.findFreeByStatus(
                organisationId,
                BlockchainPublishStatus.toDispatchStatuses(),
                LocalDateTime.now(clock).minus(lockTimeoutDuration),
                Limit.of(pullBatchSize));

        Set<AuthBeginEntity> toDispatch = dispatchingStrategy.apply(organisationId, free);

        LocalDateTime now = LocalDateTime.now(clock);
        toDispatch.forEach(entity -> entity.setLockedAt(now));

        return toDispatch.stream()
                .map(AuthBeginEntity::getId)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<AuthBeginEntity> findAllByIdsPreservingOrder(Set<String> ids) {
        Map<String, AuthBeginEntity> byId = authBeginEntityRepository.findAllById(ids).stream()
                .collect(toMap(AuthBeginEntity::getId, identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<AuthBeginEntity> findDispatchedAuthBeginsThatAreNotFinalizedYet(String organisationId, Limit limit) {
        return authBeginEntityRepository.findDispatchedThatAreNotFinalizedYet(organisationId, notFinalisedButVisibleOnChain(), limit);
    }

    /**
     * Idempotent per ceremony: a redelivered {@code AuthBeginPublishCommand} must not queue a second
     * transaction for a ceremony that already has one. An explicit user retry is a different ceremony
     * attempt and re-emits with the same ceremony id, so a row already dispatched is left alone —
     * publishing AUTH_BEGIN twice for one ceremony costs ADA and gains nothing.
     */
    @Transactional
    public void storeIfAbsent(AuthBeginEntity entity) {
        if (authBeginEntityRepository.existsById(entity.getId())) {
            log.info("AUTH_BEGIN already queued for ceremony:{} - ignoring duplicate publish command", entity.getId());

            return;
        }
        authBeginEntityRepository.save(entity);
    }

    @Transactional
    public void store(AuthBeginEntity entity) {
        authBeginEntityRepository.save(entity);
    }

    @Transactional
    public void storeAll(Set<AuthBeginEntity> entities) {
        authBeginEntityRepository.saveAll(entities);
    }

    @Transactional
    public void unlock(Set<AuthBeginEntity> batch) {
        batch.forEach(entity -> entity.setLockedAt(null));
        authBeginEntityRepository.saveAll(batch);
    }

}
