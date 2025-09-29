package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;

public interface VatRepository extends JpaRepository<Vat, Vat.Id> {

    @Query("SELECT t FROM Vat t WHERE t.id.organisationId = :organisationId")
    List<Vat> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT t FROM Vat t WHERE t.id = :Id AND t.active = :active ")
    Optional<Vat> findByIdAndActive(@Param("Id") Vat.Id Id, @Param("active") boolean active);
}
