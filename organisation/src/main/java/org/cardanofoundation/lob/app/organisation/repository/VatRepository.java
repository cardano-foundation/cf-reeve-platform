package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;

public interface VatRepository extends JpaRepository<Vat, Vat.Id> {

    @Query("SELECT t FROM Vat t WHERE t.id.organisationId = :organisationId")
    List<Vat> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("""
        SELECT t FROM Vat t WHERE t.id.organisationId = :organisationId
        AND (:customerCode IS NULL OR LOWER(t.id.customerCode) LIKE LOWER(CONCAT('%', CAST(:customerCode AS string), '%')))
        AND (:minRate IS NULL OR t.rate >= :minRate)
        AND (:maxRate IS NULL OR t.rate <= :maxRate)
        AND (:description IS NULL OR LOWER(t.description) LIKE LOWER(CONCAT('%', CAST(:description AS string), '%')))
        AND (:countryCodes IS NULL OR LOWER(t.countryCode) IN :countryCodes)
        AND (:active IS NULL OR t.active = :active)
        """)
    Page<Vat> findAllByOrganisationId(@Param("organisationId") String organisationId,
                                      @Param("customerCode") String customerCode,
                                      @Param("minRate") Double minRate,
                                      @Param("maxRate") Double maxRate,
                                      @Param("description") String description,
                                      @Param("countryCodes") List<String> countryCodes,
                                      @Param("active") Boolean active,
                                      Pageable pageable);

    @Query("SELECT t FROM Vat t WHERE t.id = :Id AND t.active = :active ")
    Optional<Vat> findByIdAndActive(@Param("Id") Vat.Id Id, @Param("active") boolean active);
}
