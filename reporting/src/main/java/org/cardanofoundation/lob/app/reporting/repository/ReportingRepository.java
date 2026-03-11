package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseStatisticView;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@Repository
public interface ReportingRepository extends JpaRepository<ReportEntity, String> {

    List<ReportEntity> findByOrganisationId(String organisationId);

    List<ReportEntity> findByReportTemplateId(String reportTemplateId);

    Optional<ReportEntity> findByOrganisationIdAndId(String organisationId, String id);

    @Query("SELECT r FROM ReportEntity r WHERE r.organisationId = :organisationId " +
            "AND r.reportTemplate.id = :reportTemplateId " +
            "AND r.intervalType = :intervalType " +
            "AND r.year = :year " +
            "AND r.period = :period " +
            "ORDER BY r.ver DESC LIMIT 1")
    Optional<ReportEntity> findLatestByTemplateAndPeriod(
            @Param("organisationId") String organisationId,
            @Param("reportTemplateId") String reportTemplateId,
            @Param("intervalType") IntervalType intervalType,
            @Param("year") short year,
            @Param("period") short period
    );

    @Query("""
            SELECT DISTINCT r FROM ReportEntity r
            JOIN r.reportTemplate.fields rf
            JOIN rf.mappingAccounts coa
            WHERE coa.id.customerCode IN (
                SELECT coa.id.customerCode
                FROM ChartOfAccount coa2
                JOIN accounting_reporting_core.TransactionItemEntity item
                ON (coa2.id.customerCode = item.accountDebit.code OR coa2.id.customerCode = item.accountCredit.code)
                WHERE item.transaction.id IN :txIds
            )
        """)
    List<ReportEntity> findAffectedByTxId(@Param("txIds") List<String> txIds);

    @Query("""
        SELECT r FROM ReportEntity r
                WHERE r.organisationId = :organisationId
                AND (:reportId IS NULL OR r.id = :reportId)
                AND (:years IS NULL OR r.year IN :years)
                AND (:intervalTypes IS NULL OR r.intervalType IN :intervalTypes)
                AND (:periods IS NULL OR r.period IN :periods)
                AND (:ledgerStatus IS NULL OR r.ledgerDispatchStatus = :ledgerStatus)
                AND (:reportTypes IS NULL OR r.reportTemplate.reportTemplateType IN :reportTypes)
                AND (:reportTemplateIds IS NULL OR r.reportTemplate.id IN :reportTemplateIds)
                AND (:txHash IS NULL OR r.blockchainHash LIKE LOWER(CONCAT('%', CAST(:txHash AS string), '%')))
                AND (:isReadyToPublish IS NULL OR r.isReadyToPublish = :isReadyToPublish)
                AND (:ledgerDispatchApproved IS NULL OR r.ledgerDispatchApproved = :ledgerDispatchApproved)
        """)
    Page<ReportEntity> findAll(@Param("organisationId") String organisationId,
                               @Param("reportId") String reportId,
                               @Param("years") List<Short> years,
                               @Param("intervalTypes") List<IntervalType> intervalTypes,
                               @Param("periods") List<Short> periods, @Param("ledgerStatus") LedgerDispatchStatus ledgerStatus,
                               @Param("reportTypes") List<ReportTemplateType> reportTypes,
                               @Param("reportTemplateIds") List<String> reportTemplateIds,
                               @Param("txHash") String txHash,
                               @Param("isReadyToPublish") Boolean isReadyToPublish,
                               @Param("ledgerDispatchApproved") Boolean ledgerDispatchApproved, Pageable pageable);

    @Query("""
            SELECT new org.cardanofoundation.lob.app.reporting.dto.ReportResponseStatisticView (
                CAST(COUNT(DISTINCT CASE WHEN r.isReadyToPublish = true AND r.ledgerDispatchApproved = false THEN r.id END) AS java.lang.Long),
                CAST(COUNT(DISTINCT CASE WHEN r.isReadyToPublish = false THEN r.id END) AS java.lang.Long),
                CAST(COUNT(DISTINCT CASE WHEN r.ledgerDispatchApproved = true THEN r.id END) AS java.lang.Long),
                CAST(COUNT(r.id) AS java.lang.Long)
             )
                FROM ReportEntity r
                WHERE
                r.organisationId = :organisationId

            """)
    ReportResponseStatisticView findStatistics(
            @Param("organisationId") String organisationId);
}
