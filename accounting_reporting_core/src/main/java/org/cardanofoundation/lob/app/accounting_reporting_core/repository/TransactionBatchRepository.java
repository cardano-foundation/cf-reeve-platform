package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;

public interface TransactionBatchRepository extends JpaRepository<TransactionBatchEntity, String>, CustomTransactionBatchRepository {

    List<TransactionBatchEntity> findAllByFilteringParametersOrganisationId(String organisationId);

    @Query(
            value = """
                    SELECT tb FROM accounting_reporting_core.TransactionBatchEntity tb
                    JOIN accounting_reporting_core.TransactionEntity tx
                    JOIN accounting_reporting_core.TransactionItemEntity ti
                    WHERE
                    tb.filteringParameters.organisationId = :organisationId
                    AND :fromDate = NULL OR tb.filteringParameters.from >= :fromDate
                    AND :toDate = NULL OR tb.filteringParameters.to <= :toDate
                    AND :transactionTypes = NULL OR tx.transactionType IN :transactionTypes
                    AND :txStatuses = NULL OR tx.overallStatus IN :txStatuses
                    AND :hasApprove = NULL AND :hasPending = NULL AND :hasInvalid = NULL AND :hasPublish = NULL AND :hasPublished = NULL AND :hasDispatched = NULL
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
                                AND ti2.rejection.rejectionReason IN (:erpRejectionReasons)
                            )
                        )
                    GROUP BY tb.id
                    """
    )
    List<TransactionBatchEntity> filterBatchEntities(@Param("organisationId") String organisationId,
                                                     @Param("fromDate") LocalDate fromDate,
                                                     @Param("toDate") LocalDate toDate,
                                                     @Param("transactionTypes") Set<TransactionType> transactionTypes,
                                                     @Param("txStatuses") Set<TransactionStatus> txStatuses,
                                                     @Param("hasInvalid") Boolean hasInvalid,
                                                     @Param("hasPending") Boolean hasPending,
                                                     @Param("hasApprove") Boolean hasApprove,
                                                     @Param("hasPublish") Boolean hasPublish,
                                                     @Param("hasPublished") Boolean hasPublished,
                                                     @Param("hasDispatched") Boolean hasDispatched,
                                                     @Param("erpRejectionReasons") Set<RejectionReason> erpRejectionReasons,
                                                     @Param("lobRejectionReasons") Set<RejectionReason> lobRejectionReasons,
                                                     Pageable pageable);

}
