package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;

public interface CurrencyRepository extends JpaRepository<Currency, Currency.Id> {

    @Query("SELECT t FROM Currency t WHERE t.id.organisationId = :organisationId")
    Set<Currency> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("""
            SELECT t FROM Currency t WHERE t.id.organisationId = :organisationId
            AND (:code IS NULL OR LOWER(t.id.code) LIKE LOWER(CONCAT('%', CAST(:code AS string), '%')))
            AND (:isoCodes IS NULL OR t.isoCode IN :isoCodes)
            """)
    Page<Currency> findAllByOrganisationId(@Param("organisationId") String organisationId, @Param("code") String customerCode, @Param("isoCodes") List<String> isoCodes, Pageable pageable);

    @Query("SELECT t FROM Currency t WHERE t.id.organisationId = :organisationId AND t.isoCode = :isoCode")
    Optional<Currency> findByCurrencyId(
        @Param("organisationId") String organisationId,
        @Param("isoCode") String isoCode
    );

}
