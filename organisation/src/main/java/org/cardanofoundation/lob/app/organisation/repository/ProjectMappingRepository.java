package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.Project;

public interface ProjectMappingRepository extends JpaRepository<Project, Project.Id> {
    @Query("SELECT t FROM Project t WHERE t.id.organisationId = :organisationId")
    Set<Project> findAllByOrganisationId(@Param("organisationId") String organisationId);
}
