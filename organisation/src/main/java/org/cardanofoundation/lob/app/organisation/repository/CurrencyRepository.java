package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;

public interface CurrencyRepository extends JpaRepository<Currency, Currency.Id> {

    @Query("SELECT t FROM Currency t WHERE t.id.organisationId = :organisationId")
    Set<Currency> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT t FROM Currency t WHERE t.id.organisationId = :organisationId AND t.currencyId = :currencyId")
    Optional<Currency> findByCurrencyId(
        @Param("organisationId") String organisationId,
        @Param("currencyId") String currencyId
    );

}
