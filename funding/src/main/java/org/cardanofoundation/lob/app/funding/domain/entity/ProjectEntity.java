package org.cardanofoundation.lob.app.funding.domain.entity;

import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

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
    @Column(name = "project_uid", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @NotBlank
    @Column(name = "funding_id", nullable = false)
    private String fundingId;

    @NotBlank
    @Column(name = "project_id", nullable = false)
    private String projectId;

    @NotBlank
    @Column(name = "project_title", nullable = false)
    private String projectTitle;

    /** Null for root projects; populated for sub-projects. */
    @Nullable
    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    /** Null for sub-projects (they inherit their parent's currency context). */
    @Nullable
    @Column(name = "currency")
    private String currency;

    /** Self-referential: null for root projects, set for sub-projects. */
    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_project_uid")
    private ProjectEntity parentProject;

    @Builder.Default
    @OneToMany(mappedBy = "parentProject", cascade = CascadeType.ALL)
    private List<ProjectEntity> subProjects = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<MilestoneEntity> milestones = new ArrayList<>();

    public static String id(String organisationId, String projectId) {
        return digestAsHex("%s::%s".formatted(organisationId, projectId));
    }

    /** ID for a sub-project — unique within its parent. */
    public static String subId(String parentProjectId, String subProjectId) {
        return digestAsHex("%s::%s".formatted(parentProjectId, subProjectId));
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
