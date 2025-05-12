package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;

public interface ReportRepository extends JpaRepository<ReportEntity, String> {

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
             WHERE r.organisation.id = :organisationId
             AND r.idControl = :idControl""")
    Optional<ReportEntity> findByIdControl(@Param("organisationId") String organisationId, @Param("idControl") String idControl);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
             WHERE r.organisation.id = :organisationId
             AND r.idControl = :idControl
             ORDER BY r.ver DESC, r.ledgerDispatchApproved ASC
             LIMIT 1""")
    Optional<ReportEntity> findLatestByIdControl(@Param("organisationId") String organisationId, @Param("idControl") String idControl);

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
                                               @Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate);

    @Query("""
            SELECT r FROM accounting_reporting_core.report.ReportEntity r
             WHERE r.organisation.id = :organisationId
             AND r.type = :reportType
             AND r.year >= :startYear
             AND r.year <= :endYear
             AND r.ledgerDispatchStatus = 'FINALIZED'
            """)
    Set<ReportEntity> findByTypeAndWithinYearRange(@Param("organisationId") String organisationId, @Param("reportType") ReportType reportType, @Param("startYear") int startYear, @Param("endYear") int endYear);
}
