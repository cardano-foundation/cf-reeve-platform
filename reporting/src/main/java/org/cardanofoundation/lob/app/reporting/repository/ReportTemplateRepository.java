package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;

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
}
