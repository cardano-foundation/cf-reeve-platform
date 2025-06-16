package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountType;

public interface ChartOfAccountSubTypeRepository extends JpaRepository<ChartOfAccountSubType, String> {
    @Query("SELECT st FROM ChartOfAccountSubType st " +
            "WHERE st.type = :type")
    Set<ChartOfAccountSubType> findAllByType(@Param("type") ChartOfAccountType type);

    @Query("SELECT st FROM ChartOfAccountSubType st " +
            "WHERE st.organisationId = :organisationId AND st.id = :subTypeId ")
    Optional<ChartOfAccountSubType> findAllByOrganisationIdAndSubTypeId(@Param("organisationId") String organisationId, @Param("subTypeId") String subTypeId);

    @Query("SELECT st FROM ChartOfAccountSubType st " +
            "WHERE st.organisationId = :organisationId AND st.name = :name AND st.type.name = :typeName")
    Optional<ChartOfAccountSubType> findFirstByNameAndOrganisationIdAndParentName(@Param("organisationId") String organisationId, @Param("name") String name, @Param("typeName") String typeName);

    Optional<ChartOfAccountSubType> findFirstByOrganisationIdAndName(String organisationId, String name);

    @Query("SELECT MAX (st.id) FROM ChartOfAccountSubType st")
    Long getMaxId();
}
