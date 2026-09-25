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

    /** User-defined project identifier. No longer used for lookups or id generation; matching is by proId or title, and the id derives from proId. */
    @Nullable
    @Column(name = "external_project_id")
    private String externalProjectId;

    @NotBlank
    @Column(name = "project_title", nullable = false)
    private String projectTitle;

    /**
     * Permanent, human-readable identifier — set once at creation and never updated afterward,
     * regardless of later title changes. Lets the project keep being found reliably (by the
     * event-allocation flow, by CSV re-upload) after a rename, without exposing the opaque {@link #id}
     * hash as the thing users reference. See LOB-2384.
     *
     * <p>For a root project ({@link #parentProject} null), this is user-suppliable at creation
     * (defaulting to {@link #projectTitle} when omitted) — root-level codes are typically meaningful to
     * the organisation (e.g. a grant reference) and worth letting them choose. For a sub-project created
     * through the JSON API or the event-allocation flow, it is always system-assigned as
     * {@code parent.proId + "-S" + n} for a sub-project, {@code "-M" + n} for a milestone (n = {@link #nextChildSequence}, atomically incremented on the
     * parent at creation) — never user-suppliable there, since a sub-project has no equivalent natural
     * external code. CSV bulk-import is the one exception: it requires the caller to supply the value
     * (see {@code ProjectStructureService#createSubProject}'s {@code explicitProId} overload), because
     * a CSV author needs to already know it to reference the row from a later Events file. Pre-existing
     * sub-projects keep the title-based value from the LOB-2384 backfill. See
     * {@link MilestoneEntity#proId} for the same rule one level down.
     */
    @NotBlank
    @Column(name = "pro_id", nullable = false)
    private String proId;

    /**
     * Counter backing the next auto-assigned child's {@code proId} suffix (see {@link #proId}) —
     * shared between sub-projects and milestones since a project has one or the other, never both
     * (see {@code FundingValidations#milestonesXorSubProjects}). Incremented under a pessimistic lock
     * on this row (see {@code FundingProjectRepository#findWithLockById}) so concurrent child creation
     * never assigns the same number twice.
     */
    @Builder.Default
    @Column(name = "next_child_sequence", nullable = false)
    private int nextChildSequence = 0;

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

    /**
     * Deterministic id for a root project — unique per organisation by its {@link #proId}, never its
     * title (which is freely editable, so can't be part of a permanent key). Rows that predate proId
     * have {@code proId == title}, so their existing ids already follow this rule.
     */
    public static String id(String organisationId, String proId) {
        return SHA3.digestAsHex("%s::%s".formatted(organisationId, proId));
    }

    /** Deterministic id for a sub-project — unique within its parent by its {@link #proId}; see {@link #id(String, String)}. */
    public static String subId(String parentProjectId, String proId) {
        return SHA3.digestAsHex("%s::%s".formatted(parentProjectId, proId));
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
