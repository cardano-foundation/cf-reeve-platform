package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;

public interface ReportTypeRepository extends JpaRepository<ReportTypeEntity, Long> {

    @Query("""
            SELECT rse
            FROM ReportTypeEntity rse
            WHERE rse.organisationId = :organisationID
            AND rse.name = :name
        """)
    Optional<ReportTypeEntity> findByOrganisationAndReportName(@Param("organisationID") String organisationID, @Param("name") String name);

    @Query("""
            SELECT rse
            FROM ReportTypeEntity rse
            WHERE rse.organisationId = :organisationID
            AND rse.id = :id
        """)
    Optional<ReportTypeEntity> findByOrganisationIdAndId(@Param("organisationID") String organisationId, @Param("id") Long id);

    List<ReportTypeEntity> findAllByOrganisationId(String orgId);
}
