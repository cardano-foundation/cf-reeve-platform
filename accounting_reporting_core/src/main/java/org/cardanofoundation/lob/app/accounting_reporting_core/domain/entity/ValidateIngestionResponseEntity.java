package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An ERP adapter's answer to a validation request, kept until the replica serving the HTTP request
 * reads it. The answer is consumed by whichever replica Kafka assigns, so it cannot live in memory.
 */
@Entity(name = "accounting_reporting_core.ValidateIngestionResponseEntity")
@Table(name = "accounting_core_validate_ingestion_response")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ValidateIngestionResponseEntity {

    @Id
    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "valid", nullable = false)
    private boolean valid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "errors", nullable = false, columnDefinition = "jsonb")
    private List<String> errors;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
