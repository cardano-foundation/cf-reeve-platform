package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.jmolecules.event.annotation.DomainEvent;


@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@DomainEvent
@Builder
public class ValidateIngestionEvent {
    // This class extends ScheduledIngestionEvent to inherit its properties and methods.
    // It is used to validate the ingestion of data before it is processed further.
    // An additional event is necessary to handle validation separately from the actual ingestion process,
    // allowing for more granular control over the data validation lifecycle.

    private String correlationId;
    private ScheduledIngestionEvent scheduledIngestionEvent;
}
