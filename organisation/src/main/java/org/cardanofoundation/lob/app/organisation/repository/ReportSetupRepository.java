package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReportSetupEntity;

public interface ReportSetupRepository extends JpaRepository<ReportSetupEntity, Long> {

    @Query("""
            SELECT rse
            FROM ReportSetupEntity rse
            WHERE rse.organisationId = :organisationID
            AND rse.name = :name
        """)
    Optional<ReportSetupEntity> findByOrganisationAndReportName(@Param("organisationID") String organisationID, @Param("name") String name);
}
