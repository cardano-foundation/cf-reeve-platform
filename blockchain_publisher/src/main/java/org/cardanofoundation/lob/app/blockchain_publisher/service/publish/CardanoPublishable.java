package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.DispatchingStrategy;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.ImmediateDispatchingStrategy;

/**
 * Per-type plug ("factory/config") for the generic Cardano publishing engine.
 *
 * <p>Each publishable type (transactions, reports, spending events, ...) provides exactly one Spring bean
 * implementing this interface. The generic {@link CardanoDispatcher} and {@link CardanoStatusWatcher} inject
 * {@code List<CardanoPublishable<?>>} and drive every registered type through the same pipeline: fetch ready
 * entities, lock, build the L1 transaction, submit, update status, notify the source module - none of which
 * needs to be re-implemented per type.
 *
 * <p>Adding a fourth type therefore means implementing this interface (plus an entity + migration); the engine
 * itself never changes.
 *
 * @param <E> the publishable entity type handled by this module
 */
public interface CardanoPublishable<E extends PublishableEntity> {

    /** Human readable label, also used for per-type config / logging. */
    String type();

    // --- dispatch side ---------------------------------------------------------------------------------------

    /** Entities ready to be (re)dispatched (STORED / ROLLBACKED), respecting any locking window. */
    Set<E> findReadyToDispatch(String organisationId, int batchSize);

    /**
     * Split the entities to dispatch into independent units, each of which becomes ONE Cardano transaction.
     * Batched types (transactions, spending events) return a single set; one-per-tx types (reports) return one
     * singleton set per entity.
     */
    Collection<Set<E>> groupForDispatch(Set<E> toDispatch);

    /** Build (serialise + sign) the Cardano transaction for a single dispatch unit. */
    Either<ProblemDetail, Optional<L1Batch<E>>> buildL1Transaction(String organisationId, Set<E> unit);

    // --- watchdog side ---------------------------------------------------------------------------------------

    /** Entities already submitted but not finalised yet, for on-chain status polling. */
    Set<E> findNotFinalizedYet(String organisationId, Limit limit);

    // --- persistence + locking -------------------------------------------------------------------------------

    void store(E entity);

    void storeAll(Set<E> entities);

    default boolean supportsLocking() {
        return false;
    }

    default void lock(Set<E> entities) {
        // no-op for types without a locking window (e.g. reports)
    }

    default void unlock(Set<E> entities) {
        // no-op for types without a locking window (e.g. reports)
    }

    // --- notification ----------------------------------------------------------------------------------------

    /** Report status changes for the given entities back to their source module. */
    void notifyLedgerUpdate(String organisationId, Set<E> entities);

    default DispatchingStrategy<E> dispatchingStrategy() {
        return new ImmediateDispatchingStrategy<>();
    }

}
