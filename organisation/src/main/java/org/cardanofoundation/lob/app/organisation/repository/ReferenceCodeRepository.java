package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;

public interface ReferenceCodeRepository extends JpaRepository<ReferenceCode, ReferenceCode.Id> {

    @Query("SELECT rc FROM ReferenceCode rc WHERE rc.id.organisationId = :orgId")
    List<ReferenceCode> findAllByOrgId(@Param("orgId") String orgId);

    @Query("""
        SELECT rc FROM ReferenceCode rc WHERE rc.id.organisationId = :orgId
        AND (:referenceCode IS NULL OR LOWER(rc.id.referenceCode) LIKE LOWER(CONCAT('%', CAST(:referenceCode AS string), '%')))
        AND (:name IS NULL OR LOWER(rc.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
        AND (:parentCodes IS NULL OR LOWER(rc.parentReferenceCode) IN :parentCodes)
        AND (:active IS NULL OR rc.isActive = :active)
        """)
    Page<ReferenceCode> findAllByOrgId(@Param("orgId") String orgId,
                                       @Param("referenceCode") String referenceCode,
                                       @Param("name") String name,
                                       @Param("parentCodes") List<String> parentCodes,
                                       @Param("active") Boolean active,
                                       Pageable pageable);

    @Query("SELECT rc FROM ReferenceCode rc WHERE rc.id.organisationId = :orgId AND rc.id.referenceCode = :referenceCode")
    Optional<ReferenceCode> findByOrgIdAndReferenceCode(@Param("orgId") String orgId, @Param("referenceCode") String referenceCode);

}
