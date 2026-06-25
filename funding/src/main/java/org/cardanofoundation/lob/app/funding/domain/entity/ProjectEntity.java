package org.cardanofoundation.lob.app.funding.domain.entity;

import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
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

    @NotBlank
    @Column(name = "funding_id", nullable = false)
    private String fundingId;

    @NotBlank
    @Column(name = "activity_id", nullable = false)
    private String activityId;

    @NotBlank
    @Column(name = "activity_title", nullable = false)
    private String activityTitle;

    @Nullable
    @Column(name = "activity_sub_id")
    private String activitySubId;

    @NotNull
    @Column(name = "expected_total_amount", nullable = false)
    private BigDecimal expectedTotalAmount;

    @NotBlank
    @Column(name = "currency", nullable = false)
    private String currency;

    @Builder.Default
    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<MilestoneEntity> milestones = new ArrayList<>();

    public static String id(String organisationId, String activityId) {
        return digestAsHex("%s::%s".formatted(organisationId, activityId));
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
