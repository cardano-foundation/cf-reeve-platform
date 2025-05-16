package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@DomainEvent
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class TransactionBatchCreatedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotBlank
    private String batchId;

    @NotBlank
    private String organisationId;

    @NotNull
    private UserExtractionParameters userExtractionParameters;

    @NotNull
    private SystemExtractionParameters systemExtractionParameters;

    private ExtractorType extractorType;

}
