package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountingRegime;

public interface AccountingRegimeRepository extends JpaRepository<AccountingRegime, AccountingRegime.Id> {

    @Query("SELECT t FROM AccountingRegime t WHERE t.id.organisationId = :organisationId")
    Set<AccountingRegime> findAllByOrganisationId(@Param("organisationId") String organisationId);

}
