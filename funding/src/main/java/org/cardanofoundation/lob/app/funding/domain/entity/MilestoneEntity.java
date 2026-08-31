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

    /** Deterministic id for a milestone — unique within its project by its title. */
    public static String id(String projectId, String milestoneTitle) {
        return SHA3.digestAsHex("%s::%s".formatted(projectId, milestoneTitle));
    }

}
