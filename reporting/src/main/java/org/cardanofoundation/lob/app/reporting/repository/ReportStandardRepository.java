package org.cardanofoundation.lob.app.reporting.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportStandardEntity;

public interface ReportStandardRepository extends JpaRepository<ReportStandardEntity, ReportStandardEntity.Id> {

    @Query("SELECT t FROM ReportStandardEntity t WHERE t.id.organisationId = :organisationId")
    Set<ReportStandardEntity> findAllByOrganisationId(@Param("organisationId") String organisationId);

}
