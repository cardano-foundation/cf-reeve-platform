package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@AllArgsConstructor
@Builder(toBuilder = true)
@DomainEvent
@Getter
@ToString
@NoArgsConstructor
public class ReconcilationStartedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotBlank
    private String reconciliationId;

    @NotBlank
    private String organisationId;

    @NotNull
    private LocalDate from;

    @NotNull
    private LocalDate to;

    private ExtractorType extractorType = ExtractorType.NETSUITE;

}
