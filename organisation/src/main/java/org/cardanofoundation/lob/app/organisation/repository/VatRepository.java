package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;

public interface VatRepository extends JpaRepository<OrganisationVat, OrganisationVat.Id> {

    @Query("SELECT t FROM OrganisationVat t WHERE t.id.organisationId = :organisationId")
    List<OrganisationVat> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT t FROM OrganisationVat t WHERE t.id = :Id AND t.active = :active ")
    Optional<OrganisationVat> findByIdAndActive(@Param("Id") OrganisationVat.Id Id, @Param("active") boolean active);
}
