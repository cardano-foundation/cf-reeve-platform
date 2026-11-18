package org.cardanofoundation.lob.app.csv_erp_adapter.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity(name = "csv_erp_adapter.CsvExtractionFileEntity")
@Table(name = "csv_erp_adapter_extraction_file")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CsvExtractionFileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organisation_id", nullable = false)
    private String organisationId;

    @Column(name = "file")
    private byte[] file;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
