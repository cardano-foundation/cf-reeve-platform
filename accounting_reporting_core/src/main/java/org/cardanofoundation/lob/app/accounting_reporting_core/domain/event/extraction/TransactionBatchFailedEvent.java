package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction;

import java.util.Optional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.*;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@AllArgsConstructor
@Builder
@DomainEvent
@Getter
@ToString
@NoArgsConstructor
@Setter
public class TransactionBatchFailedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotBlank
    private String batchId;

    @NotBlank
    private String organisationId;

    @NotNull
    private FatalError error;

    @Builder.Default
    private ExtractorType extractorType = ExtractorType.NETSUITE;

    @NotNull
    private UserExtractionParameters userExtractionParameters;

    @Builder.Default
    private Optional<SystemExtractionParameters> systemExtractionParameters = Optional.empty();

}
