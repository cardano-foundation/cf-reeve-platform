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

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;

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
            AND (:reportType IS NULL OR CAST(r.type AS string) = CAST(:reportType AS string))
            AND (:intervalType IS NULL OR  CAST(r.intervalType AS string) = CAST(:intervalType AS string))
            AND (:ledgerStatus IS NULL OR CAST(r.ledgerDispatchStatus AS string) = CAST(:ledgerStatus AS string))
            AND (:currencyCode IS NULL OR r.organisation.currencyId = :currencyCode)
            AND (:year IS NULL OR r.year = :year)
            AND (:period IS NULL OR r.period = :period)
            AND (:txHash IS NULL OR r.ledgerDispatchReceipt.primaryBlockchainHash LIKE %:txHash%)
            """)
    Page<ReportEntity> findAllByOrganisationId(@Param("organisationId") String organisationId,
            @Param("reportType") ReportType reportType, @Param("currencyCode") String currencyCode,
            @Param("intervalType") IntervalType intervalType, @Param("year") Short year,
            @Param("period") Short period,
            @Param("ledgerStatus") LedgerDispatchStatus ledgerDispatchStatus,
            @Param("txHash") String txHash, Pageable pageable);

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
                (r.intervalType = 'YEAR' AND r.year >= :year)
                OR (r.intervalType = 'QUARTER' AND ((r.year = :year AND r.period >= :quarter) OR (r.year > :year)))
                OR (r.intervalType = 'MONTH' AND ((r.year = :year AND r.period >= :month) OR (r.year > :year)))
            """)
    Set<ReportEntity> findNotPublishedByOrganisationIdAndContainingDate(
            @Param("organisationId") String organisationId, @Param("year") int year,
            @Param("quarter") int quarter, @Param("month") int month);
}
