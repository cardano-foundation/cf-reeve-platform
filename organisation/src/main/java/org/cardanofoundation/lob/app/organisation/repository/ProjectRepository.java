package org.cardanofoundation.lob.app.organisation.repository;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Project;

public interface ProjectRepository extends JpaRepository<Project, Project.Id> {
    @Query("""
            SELECT t FROM Project t WHERE t.id.organisationId = :organisationId
            AND (:customerCode IS NULL OR t.id.customerCode = :customerCode)
            AND (:name IS NULL OR t.name = :name)
            AND (:parentCustomerCode IS NULL OR t.parentCustomerCode = :parentCustomerCode)
            """)
    Page<Project> findAllByOrganisationId(@Param("organisationId") String organisationId, @Param("customerCode") String customerCode,
                                          @Param("name") String name,
                                          @Param("parentCustomerCode") String parentCustomerCode, Pageable pageable);
}
