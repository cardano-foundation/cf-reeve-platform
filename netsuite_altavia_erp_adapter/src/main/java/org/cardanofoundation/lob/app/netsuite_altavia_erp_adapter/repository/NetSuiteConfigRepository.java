package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;

@Repository
public interface NetSuiteConfigRepository extends JpaRepository<NetSuiteConfigEntity, String> {

    /**
     * Records the outcome of verifying a specific revision against NetSuite.
     * <p>
     * Deliberately a conditional update rather than a read-mutate-save. The verdict is written
     * after an outbound HTTP call, so a newer revision may have been stored in the meantime; a
     * full-row merge would restore this invocation's stale URL, client id and encrypted key over
     * the newer configuration. Scoping the write to {@code revision} makes a superseded verdict a
     * zero-row no-op, and touches only the verdict columns.
     *
     * <p>
     * {@code @Transactional} is required, not decorative. {@code SimpleJpaRepository} is annotated
     * {@code @Transactional(readOnly = true)} at class level, and custom query methods inherit it.
     * The caller, NetSuiteConfigService, is deliberately non-transactional so that an outbound HTTP
     * call never holds a database connection — so without this override the update runs in the
     * inherited read-only transaction and Hibernate throws
     * {@code TransactionRequiredException: Executing an update/delete query}.
     *
     * @param validationMessage the upstream failure detail, or {@code null} when verification
     *                          succeeded. This package is {@code @NonNullApi}, so the parameter
     *                          must opt out explicitly — otherwise Spring Data's
     *                          {@code NullnessMethodInvocationValidator} rejects the successful
     *                          case at runtime.
     * @return rows updated: 0 means the configuration has moved on to a newer revision
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE NetSuiteConfigEntity c
               SET c.validationStatus = :validationStatus,
                   c.validationMessage = :validationMessage
             WHERE c.organisationId = :organisationId
               AND c.revision = :revision
            """)
    int recordValidationVerdict(@Param("organisationId") String organisationId,
                                @Param("revision") long revision,
                                @Param("validationStatus") String validationStatus,
                                @Param("validationMessage") @Nullable String validationMessage);

}
