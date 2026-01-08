package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@Repository
public interface ReportTemplateRepository extends JpaRepository<ReportTemplateEntity, String> {

    List<ReportTemplateEntity> findByOrganisationId(String organisationId);

    Optional<ReportTemplateEntity> findByOrganisationIdAndName(String organisationId, String name);

    boolean existsByOrganisationIdAndName(String organisationId, String name);

    @Query("SELECT rt FROM ReportTemplateEntity rt WHERE rt.organisationId = :organisationId " +
            "AND rt.name = :name ORDER BY rt.ver DESC LIMIT 1")
    Optional<ReportTemplateEntity> findLatestByOrganisationIdAndName(
            @Param("organisationId") String organisationId,
            @Param("name") String name
    );

    @Query("""
            SELECT rt FROM ReportTemplateEntity rt WHERE rt.organisationId = :organisationId AND rt.name = :name AND rt.reportTemplateType = :reportTemplateType
            ORDER BY rt.ver DESC
            LIMIT 1
            """)
    Optional<ReportTemplateEntity> findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion(@Param("organisationId") String organisationId,
                                                                                   @Param("name") String name,
                                                                                   @Param("reportTemplateType") ReportTemplateType reportTemplateType);

    @Query("SELECT rt FROM ReportTemplateEntity rt WHERE rt.organisationId = :organisationId " +
            "AND rt.id = :id ORDER BY rt.ver DESC LIMIT 1")
    Optional<ReportTemplateEntity> findLatestByOrganisationIdAndId(@Param("organisationId") String organisationId, @Param("id") String id);

    @Query("""
        SELECT rt FROM ReportTemplateEntity rt
        LEFT JOIN rt.reports r
        WHERE (:organisationId IS NULL OR rt.organisationId = :organisationId)
        AND (:name IS NULL OR LOWER(rt.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
        AND (:description IS NULL OR LOWER(rt.description) LIKE LOWER(CONCAT('%', CAST(:description AS string), '%')))
        AND (:reportTemplateTypes IS NULL OR rt.reportTemplateType IN :reportTemplateTypes)
        AND (:active IS NULL OR rt.active = :active)
        AND (:dataMode IS NULL OR rt.dataMode IN :dataMode)
        """)
    Page<ReportTemplateEntity> findAll(@Param("organisationId") String organisationId, @Param("name") String name, @Param("description") String description, @Param("reportTemplateTypes") List<ReportTemplateType> reportTemplateTypes, @Param("active") Boolean active, @Param("dataMode") List<DataMode> dataMode, Pageable pageable);
}
