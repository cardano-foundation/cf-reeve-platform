package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountType;

public interface ChartOfAccountTypeRepository extends JpaRepository<ChartOfAccountType, String> {

    @Query("SELECT t FROM ChartOfAccountType t " +
            "LEFT JOIN FETCH t.subTypes tst " +
            "WHERE t.organisationId = :organisationId")
    Set<ChartOfAccountType> findAllByOrganisationId(@Param("organisationId") String organisationId);

    Optional<ChartOfAccountType> findFirstByOrganisationIdAndName(String organisationId, String name);

    @Query("SELECT MAX(t.id) FROM ChartOfAccountType t")
    Long getMaxId();
}
