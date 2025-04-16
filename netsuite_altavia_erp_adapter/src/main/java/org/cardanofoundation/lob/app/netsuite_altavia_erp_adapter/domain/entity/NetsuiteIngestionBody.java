package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "netsuite_adapter_ingestion_body")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NetsuiteIngestionBody extends CommonEntity implements Persistable<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "ingestion_body", nullable = false, length = 999_999, columnDefinition = "TEXT")
    private String ingestionBody;

    @Column(name = "netsuite_ingestion_id", nullable = false)
    private String netsuiteIngestionId;

    @Column(name = "ingestion_body_debug", nullable = false, length = 999_999, columnDefinition = "TEXT")
    private String ingestionBodyDebug;

    @Column(name = "ingestion_body_checksum", nullable = false)
    private String ingestionBodyChecksum;
}
