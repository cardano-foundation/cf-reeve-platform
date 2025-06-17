package org.cardanofoundation.lob.app.organisation.domain.entity;


import jakarta.persistence.*;

import lombok.*;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "organisation_chart_of_account_sub_type", indexes = {@Index(name = "astu_name", columnList = "name", unique = true)})
@Audited
@Builder
@EntityListeners({AuditingEntityListener.class})
public class ChartOfAccountSubType extends CommonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type", referencedColumnName = "id")
    private ChartOfAccountType type;


}
