package org.cardanofoundation.lob.app.funding.domain.entity;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import lombok.*;

import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.crypto.SHA3;
import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Entity(name = "funding.ProjectEntity")
@Table(name = "funding_project")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class ProjectEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "project_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Nullable
    @Column(name = "funding_id")
    private String fundingId;

    /** User-defined project identifier. No longer used for lookups or id generation; title-based now. */
    @Nullable
    @Column(name = "external_project_id")
    private String externalProjectId;

    @NotBlank
    @Column(name = "project_title", nullable = false)
    private String projectTitle;

    /** Null for root projects; populated for sub-projects. */
    @Nullable
    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    /**
     * Always populated after creation. For a root project it's required at creation time; for a
     * sub-project it defaults to its parent's currency when not explicitly given (see
     * {@code ProjectStructureService.createSubProject}) — nullable only because a sub-project's
     * default is resolved from its parent rather than enforced at the column level.
     */
    @Nullable
    @Column(name = "currency")
    private String currency;

    /** Self-referential: null for root projects, set for sub-projects. */
    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_project_id")
    private ProjectEntity parentProject;

    @Builder.Default
    @OneToMany(mappedBy = "parentProject", cascade = CascadeType.ALL)
    private List<ProjectEntity> subProjects = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<MilestoneEntity> milestones = new ArrayList<>();

    /** Deterministic id for a root project — unique per organisation by its title. */
    public static String id(String organisationId, String projectTitle) {
        return SHA3.digestAsHex("%s::%s".formatted(organisationId, projectTitle));
    }

    /** Deterministic id for a sub-project — unique within its parent by its title. */
    public static String subId(String parentProjectId, String projectTitle) {
        return SHA3.digestAsHex("%s::%s".formatted(parentProjectId, projectTitle));
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
