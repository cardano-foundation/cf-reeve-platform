package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

/**
 * One project allocation of a {@link SpendingEventEntity}. Mirrors a single entry of the on-chain
 * {@code allocation[]} array: {@link #projectId}/{@link #projectTitle} always carry the root project.
 * For a direct allocation the milestones belong to that project; when the allocation targets a
 * sub-project, {@link #subProjectId}/{@link #subProjectTitle} identify it and the milestones are
 * serialised nested under the {@code sub_project} object instead.
 */
@Getter
@Setter
@Entity(name = "blockchain_publisher.spending.event_project_allocation_entity")
@Table(name = "blockchain_publisher_event_project_allocation")
@Builder
@Audited
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners({ AuditingEntityListener.class })
@Access(AccessType.FIELD)
public class EventProjectAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "event_id")
    private SpendingEventEntity event;

    @NotBlank
    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Nullable
    @Column(name = "project_title")
    private String projectTitle;

    /** Set only when the allocation targets a sub-project; the sub-project's user-defined id. */
    @Nullable
    @Column(name = "sub_project_id")
    private String subProjectId;

    /** Set only when the allocation targets a sub-project; the sub-project's own title. */
    @Nullable
    @Column(name = "sub_project_title")
    private String subProjectTitle;

    @Builder.Default
    @OneToMany(mappedBy = "allocation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventMilestoneAllocationEntity> milestones = new ArrayList<>();
}
