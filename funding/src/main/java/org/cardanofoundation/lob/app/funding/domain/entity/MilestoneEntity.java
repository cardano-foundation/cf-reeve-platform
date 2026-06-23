package org.cardanofoundation.lob.app.funding.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
@Entity(name = "funding.MilestoneEntity")
@Table(name = "funding_milestone")
@Audited
@EntityListeners({AuditingEntityListener.class})
public class MilestoneEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "milestone_id", nullable = false)
    private String id;

    @NotBlank
    @Column(name = "label", nullable = false)
    private String label;

    @NotNull
    @Column(name = "expected_cost", nullable = false)
    private BigDecimal expectedCost;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @NotNull
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Override
    public boolean isNew() {
        return isNew;
    }

}
