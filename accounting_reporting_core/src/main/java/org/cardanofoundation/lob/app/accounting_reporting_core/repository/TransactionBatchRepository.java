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
                    WHERE
                        tb.filteringParameters.organisationId = :organisationId
                        AND (:transactionTypes IS NULL OR tx.transactionType IN :transactionTypes)
                        AND (:txStatuses IS NULL OR tx.overallStatus IN :txStatuses)
                        AND (
                            :hasApprove IS FALSE AND :hasPending IS FALSE AND :hasInvalid IS FALSE AND :hasPublish IS FALSE AND :hasPublished IS FALSE AND :hasDispatched IS FALSE
                            OR (
                                :hasApprove IS TRUE
                                AND tx.automatedValidationStatus = 'VALIDATED'
                            )
                            OR (
                                :hasPending IS TRUE
                                AND EXISTS(
                                    SELECT 1 FROM accounting_reporting_core.TransactionItemEntity ti
                                    WHERE ti.transaction = tx
                                    AND ti.rejection.rejectionReason IN :lobRejectionReasons
                                )
                                AND NOT EXISTS(
                                    SELECT 1 FROM accounting_reporting_core.TransactionItemEntity ti
                                    WHERE ti.transaction = tx
                                    AND ti.rejection.rejectionReason IN :erpRejectionReasons
                                )
                            )
                            OR (
                                :hasInvalid IS TRUE
                                AND EXISTS(
                                    SELECT 1 FROM accounting_reporting_core.TransactionItemEntity ti
                                    WHERE ti.transaction = tx
                                    AND ti.rejection.rejectionReason IN :erpRejectionReasons
                                )
                            )
                            OR (
                                :hasPublish IS TRUE
                                AND tx.automatedValidationStatus = 'VALIDATED'
                                AND tx.transactionApproved IS TRUE
                            )
                            OR (
                                :hasPublished IS TRUE
                                AND tx.automatedValidationStatus = 'VALIDATED'
                                AND tx.transactionApproved IS TRUE
                                AND tx.ledgerDispatchApproved IS TRUE
                            )
                            OR (
                                :hasDispatched IS TRUE
                                AND tx.automatedValidationStatus = 'VALIDATED'
                                AND tx.transactionApproved IS TRUE
                                AND tx.ledgerDispatchApproved IS TRUE
                                AND tx.ledgerDispatchStatus = 'FINALIZED'
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
