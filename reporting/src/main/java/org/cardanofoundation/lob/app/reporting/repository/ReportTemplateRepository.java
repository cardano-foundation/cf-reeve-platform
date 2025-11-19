package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;

@Repository
public interface ReportTemplateRepository extends JpaRepository<ReportTemplateEntity, Long> {

    List<ReportTemplateEntity> findByOrganisationId(String organisationId);

    Optional<ReportTemplateEntity> findByOrganisationIdAndName(String organisationId, String name);

    boolean existsByOrganisationIdAndName(String organisationId, String name);
}
