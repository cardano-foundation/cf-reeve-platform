package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;

public interface TransactionBatchRepository extends JpaRepository<TransactionBatchEntity, String>, CustomTransactionBatchRepository {

    List<TransactionBatchEntity> findAllByFilteringParametersOrganisationId(String organisationId);

    @Query(
            value = """
                    SELECT tb FROM accounting_reporting_core.TransactionBatchEntity tb
                    JOIN accounting_reporting_core.TransactionEntity tx on tb.id = tx.batchId
                    JOIN accounting_reporting_core.TransactionItemEntity ti on tx.id = ti.transaction.id
                    WHERE
                        tb.filteringParameters.organisationId = :organisationId
                        AND (:transactionTypes IS NULL OR tx.transactionType IN :transactionTypes)
                        AND (:txStatuses IS NULL OR tx.overallStatus IN :txStatuses)
                        AND (
                            :hasApprove IS NULL AND :hasPending IS NULL AND :hasInvalid IS NULL AND :hasPublish IS NULL AND :hasPublished IS NULL AND :hasDispatched IS NULL
                            OR (
                                :hasApprove = TRUE
                                AND ti.rejection.rejectionReason NOT IN :lobRejectionReasons
                                AND ti.rejection.rejectionReason NOT IN :erpRejectionReasons
                            )
                            OR (
                                :hasPending = TRUE
                                AND EXISTS(
                                    SELECT 1 FROM accounting_reporting_core.TransactionItemEntity ti2
                                    WHERE ti2.transaction = tx
                                    AND ti2.rejection.rejectionReason IN :lobRejectionReasons
                                )
                            )
                            OR (
                                :hasInvalid = TRUE
                                AND EXISTS(
                                    SELECT 1 FROM accounting_reporting_core.TransactionItemEntity ti2
                                    WHERE ti2.transaction = tx
                                    AND ti2.rejection.rejectionReason IN :erpRejectionReasons
                                )
                            )
                        )
                        GROUP BY tb.id
                    """
    )
    List<TransactionBatchEntity> filterBatchEntities(
                                                    @Param("organisationId") String organisationId,
                                                    @Param("transactionTypes") Set<String> transactionTypes,
                                                    @Param("txStatuses") Set<String> txStatuses,
                                                    @Param("hasApprove") Boolean hasApprove,
                                                    @Param("hasPending") Boolean hasPending,
                                                    @Param("hasInvalid") Boolean hasInvalid,
                                                    @Param("hasPublish") Boolean hasPublish,
                                                    @Param("hasPublished") Boolean hasPublished,
                                                    @Param("hasDispatched") Boolean hasDispatched,
                                                    @Param("lobRejectionReasons") Set<RejectionReason> lobRejectionReasons,
                                                    @Param("erpRejectionReasons") Set<RejectionReason> erpRejectionReasons,
                                                     Pageable pageable);

}
