package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
@Entity(name = "funding.MilestoneEntity")
@Table(name = "funding_milestone")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class MilestoneEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "milestone_id", nullable = false)
    private String id;

    /** User-defined milestone identifier (e.g. "MS-1"). No longer used for lookups or id generation; title-based now. */
    @Nullable
    @Column(name = "external_milestone_id")
    private String externalMilestoneId;

    @NotBlank
    @Column(name = "milestone_title", nullable = false)
    private String milestoneTitle;

    /**
     * Permanent, human-readable identifier — set once at creation and never updated afterward,
     * regardless of later title changes. Through the JSON API and the event-allocation flow it is
     * always system-assigned as {@code project.proId + "-" + n} (never user-suppliable there — a
     * milestone has no equivalent natural external code, unlike a root project). CSV bulk-import is the
     * one exception: it requires the caller to supply the value (see
     * {@code MilestoneService#create(String, MilestoneCreateRequest, String)}). Pre-existing milestones
     * keep the title-based value from the LOB-2384 backfill. See {@link ProjectEntity#proId}; LOB-2384.
     */
    @NotBlank
    @Column(name = "pro_id", nullable = false)
    private String proId;

    /**
     * Free-text description of the milestone's scope/deliverable — part of the locked/editable field
     * set alongside {@link #milestoneAmount} and {@link #milestoneDate}: read-only once a published
     * event allocates to this milestone (see {@code MilestoneService#update}; LOB-2365).
     */
    @Nullable
    @Column(name = "description")
    private String description;

    @NotNull
    @Column(name = "milestone_amount", nullable = false)
    private BigDecimal milestoneAmount;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @NotNull
    @Column(name = "milestone_date", nullable = false)
    private LocalDate milestoneDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * Deterministic id for a milestone — unique within its project by its {@link #proId}, never its
     * title (which is freely editable, so can't be part of a permanent key). Rows that predate proId
     * have {@code proId == title}, so their existing ids already follow this rule.
     */
    public static String id(String projectId, String proId) {
        return SHA3.digestAsHex("%s::%s".formatted(projectId, proId));
    }

}
