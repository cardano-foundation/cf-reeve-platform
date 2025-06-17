package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

import lombok.*;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@DomainEvent
@Builder
public class ScheduledIngestionEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotNull
    private String organisationId;

    @NotNull
    private UserExtractionParameters userExtractionParameters;

    @NotNull
    private ExtractorType extractorType;
    @ToString.Exclude
    private byte[] file;

    private Map<String, Object> parameters;

}
