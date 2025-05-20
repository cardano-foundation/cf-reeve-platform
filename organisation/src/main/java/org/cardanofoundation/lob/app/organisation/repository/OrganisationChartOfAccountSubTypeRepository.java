package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountType;

public interface OrganisationChartOfAccountSubTypeRepository extends JpaRepository<OrganisationChartOfAccountSubType, String> {
    @Query("SELECT st FROM OrganisationChartOfAccountSubType st " +
            "WHERE st.type = :type")
    Set<OrganisationChartOfAccountSubType> findAllByType(@Param("type") OrganisationChartOfAccountType type);

    @Query("SELECT st FROM OrganisationChartOfAccountSubType st " +
            "WHERE st.organisationId = :organisationId AND st.id = :subTypeId ")
    Optional<OrganisationChartOfAccountSubType> findAllByOrganisationIdAndSubTypeId(@Param("organisationId") String organisationId, @Param("subTypeId") String subTypeId);

    @Query("SELECT st FROM OrganisationChartOfAccountSubType st " +
            "WHERE st.organisationId = :organisationId AND st.name = :name AND st.type.name = :typeName")
    Optional<OrganisationChartOfAccountSubType> findFirstByNameAndOrganisationIdAndParentName(@Param("organisationId") String organisationId, @Param("name") String name, @Param("typeName") String typeName);

    Optional<OrganisationChartOfAccountSubType> findFirstByOrganisationIdAndName(String organisationId, String name);

    @Query("SELECT MAX (st.id) FROM OrganisationChartOfAccountSubType st")
    Long getMaxId();
}
