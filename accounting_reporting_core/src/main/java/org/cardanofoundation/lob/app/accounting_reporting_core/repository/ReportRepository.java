package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseStatisticView;

public interface ReportRepository extends JpaRepository<ReportEntity, String> {

    Optional<ReportEntity> findFirstByOrganisationIdAndReportId(
            @Param("organisationId") String organisationId, @Param("reportId") String reportId);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
            WHERE r.organisation.id = :organisationId
            AND r.ledgerDispatchStatus = 'NOT_DISPATCHED'
            AND r.ledgerDispatchApproved = true
            ORDER BY r.createdAt ASC, r.reportId ASC""")
    Set<ReportEntity> findDispatchableReports(@Param("organisationId") String organisationId,
            Limit limit);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
            LEFT JOIN accounting_reporting_core.report.ReportEntity r2 on r.idControl = r2.idControl and r.ver < r2.ver
            WHERE r.organisation.id = :organisationId
            AND r2.idControl IS NULL
            ORDER BY r.createdAt ASC, r.reportId ASC""")
    Set<ReportEntity> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
            LEFT JOIN accounting_reporting_core.report.ReportEntity r2 on r.idControl = r2.idControl and r.ver < r2.ver
             WHERE r.organisation.id = :organisationId
             AND r2.idControl IS NULL
            AND (:reportType IS NULL OR r.type IN :reportType)
            AND (:intervalType IS NULL OR r.intervalType IN :intervalType)
            AND (:ledgerStatus IS NULL OR CAST(r.ledgerDispatchStatus AS string) = :ledgerStatus)
            AND (:currencyCode IS NULL OR r.organisation.currencyId IN :currencyCode)
            AND (:year IS NULL OR r.year IN :year)
            AND (:period IS NULL OR r.period IN :period)
            AND (:readyToPublish IS NULL OR r.isReadyToPublish = :readyToPublish)
            AND (:ledgerDispatchApproved IS NULL OR r.ledgerDispatchApproved = :ledgerDispatchApproved)
            AND (:txHash IS NULL OR LOWER(r.ledgerDispatchReceipt.primaryBlockchainHash) LIKE LOWER(CONCAT('%', CAST(:txHash AS string), '%')))
            """)
    Page<ReportEntity> findAllByOrganisationId(@Param("organisationId") String organisationId,
                                               @Param("reportType") List<ReportType> reportType,
                                               @Param("currencyCode") List<String> currencyCode,
                                               @Param("intervalType") List<IntervalType> intervalType,
                                               @Param("year") List<Short> year,
                                               @Param("period") List<Short> period,
                                               @Param("ledgerStatus") String ledgerDispatchStatus,
                                               @Param("txHash") String txHash,
                                               @Param("readyToPublish") Boolean readyToPublish,
                                               @Param("ledgerDispatchApproved") Boolean ledgerDispatchApproved,
                                               Pageable pageable);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
             WHERE r.organisation.id = :organisationId
             AND r.idControl = :idControl""")
    Optional<ReportEntity> findByIdControl(@Param("organisationId") String organisationId,
            @Param("idControl") String idControl);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
             WHERE r.organisation.id = :organisationId
             AND r.idControl = :idControl
             ORDER BY r.ver DESC, r.ledgerDispatchApproved ASC
             LIMIT 1""")
    Optional<ReportEntity> findLatestByIdControl(@Param("organisationId") String organisationId,
            @Param("idControl") String idControl);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
            JOIN (
                SELECT MAX(r2.ver) AS ver, r2.reportId as id FROM accounting_reporting_core.report.ReportEntity r2
                WHERE r2.organisation.id = :organisationId
                AND (CAST(:startDate AS date) IS NULL OR r2.date >= :startDate)
                AND (CAST(:endDate AS date)  IS NULL OR r2.date <= :endDate)
                AND r2.ledgerDispatchApproved = true
                GROUP BY r2.reportId
                ) AS latest
            ON r.ver = latest.ver AND r.reportId = latest.id
            WHERE r.organisation.id = :organisationId
            AND (CAST(:startDate AS date) IS NULL OR r.date >= :startDate)
            AND (CAST(:endDate AS date)  IS NULL OR r.date <= :endDate)
            AND r.ledgerDispatchApproved = true
            """)
    List<ReportEntity> getNewestReportsInRange(@Param("organisationId") String organisationId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
             WHERE r.organisation.id = :organisationId
             AND r.type = :reportType
             AND r.year >= :startYear
             AND r.year <= :endYear
             AND r.ledgerDispatchStatus = 'FINALIZED'
            """)
    Set<ReportEntity> findByTypeAndWithinYearRange(@Param("organisationId") String organisationId,
            @Param("reportType") ReportType reportType, @Param("startYear") int startYear,
            @Param("endYear") int endYear);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
             WHERE
             r.organisation.id = :organisationId
             AND r.ledgerDispatchStatus = 'NOT_DISPATCHED'
             AND
                (r.intervalType = 'YEAR' AND r.year >= :year
                OR (r.intervalType = 'QUARTER' AND ((r.year = :year AND r.period >= :quarter) OR (r.year > :year)))
                OR (r.intervalType = 'MONTH' AND ((r.year = :year AND r.period >= :month) OR (r.year > :year)))
                )
            """)
    Set<ReportEntity> findNotPublishedByOrganisationIdAndContainingDate(
            @Param("organisationId") String organisationId, @Param("year") int year,
            @Param("quarter") int quarter, @Param("month") int month);

    @Query("""
            SELECT new org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.ReportResponseStatisticView (
                CAST(COUNT(DISTINCT CASE WHEN r.isReadyToPublish = false THEN r.id END) AS java.lang.Long),
                CAST(COUNT(DISTINCT CASE WHEN r.isReadyToPublish = true THEN r.id END) AS java.lang.Long),
                CAST(COUNT(DISTINCT CASE WHEN r.ledgerDispatchApproved = true THEN r.id END) AS java.lang.Long),
                CAST(COUNT(r.id) AS java.lang.Long)
             )
                FROM accounting_reporting_core.report.ReportEntity r
                WHERE
                r.organisation.id = :organisationId

            """)
    ReportResponseStatisticView findStatistics(
            @Param("organisationId") String organisationId);
}
