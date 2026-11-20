package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Standard;

public interface StandardRepository extends JpaRepository<Standard, Standard.Id> {

    @Query("SELECT t FROM Standard t WHERE t.id.organisationId = :organisationId")
    Set<Standard> findAllByOrganisationId(@Param("organisationId") String organisationId);

}
