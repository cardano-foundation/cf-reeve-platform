package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;

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
}
