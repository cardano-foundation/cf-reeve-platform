package org.cardanofoundation.lob.app.organisation.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;
import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteSyncState;

@Repository
public interface NetSuiteConfigStateRepository extends JpaRepository<NetSuiteConfigState, String> {

    /**
     * Records the netsuite module's verdict, but only against the revision it was produced for.
     * <p>
     * Deliberately a targeted conditional update rather than a read-mutate-save. Two reasons:
     * Hibernate would otherwise write every column, so an acknowledgement in flight for an older
     * revision would revert the configuration an admin has just committed; and the {@code revision}
     * predicate makes a superseded acknowledgement a zero-row no-op instead of an optimistic-lock
     * failure, which would mark the transaction rollback-only and surface as an
     * {@code UnexpectedRollbackException} at commit.
     *
     * @return the number of rows updated: 0 means unknown organisation or superseded revision
     */
    @Modifying
    @Query("""
            UPDATE NetSuiteConfigState s
               SET s.syncState = :syncState,
                   s.syncMessage = :syncMessage,
                   s.netsuiteValid = :netsuiteValid,
                   s.validationMessage = :validationMessage,
                   s.lastValidatedAt = :lastValidatedAt,
                   s.updatedAt = :updatedAt
             WHERE s.organisationId = :organisationId
               AND s.revision = :revision
            """)
    int applyAcknowledgement(@Param("organisationId") String organisationId,
                             @Param("revision") long revision,
                             @Param("syncState") NetSuiteSyncState syncState,
                             @Param("syncMessage") String syncMessage,
                             @Param("netsuiteValid") Boolean netsuiteValid,
                             @Param("validationMessage") String validationMessage,
                             @Param("lastValidatedAt") Instant lastValidatedAt,
                             @Param("updatedAt") Instant updatedAt);

}
