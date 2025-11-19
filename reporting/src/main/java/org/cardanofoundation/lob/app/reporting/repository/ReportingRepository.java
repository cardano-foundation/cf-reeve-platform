package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;

@Repository
public interface ReportingRepository extends JpaRepository<ReportEntity, Long> {

    List<ReportEntity> findByOrganisationId(String organisationId);

    List<ReportEntity> findByReportTemplateId(Long reportTemplateId);

    Optional<ReportEntity> findByOrganisationIdAndId(String organisationId, Long id);
}
