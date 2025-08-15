package org.cardanofoundation.lob.app.organisation.domain.entity;

import java.util.LinkedHashSet;
import java.util.Set;

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
@Table(name = "organisation_chart_of_account_type", indexes = {@Index(name = "atu_name", columnList = "name", unique = true)})
@Audited
@Builder
@EntityListeners({AuditingEntityListener.class})
public class ChartOfAccountType extends CommonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "name", nullable = false)
    private String name;

    @OneToMany(mappedBy = "type", orphanRemoval = true, fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @Builder.Default
    private Set<ChartOfAccountSubType> subTypes = new LinkedHashSet<>();


}
