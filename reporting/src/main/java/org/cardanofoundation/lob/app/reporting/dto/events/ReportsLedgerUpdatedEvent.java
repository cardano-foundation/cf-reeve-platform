package org.cardanofoundation.lob.app.reporting.dto.events;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.*;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.reporting.model.ReportStatusUpdate;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@ToString
@DomainEvent
@Builder
public final class ReportsLedgerUpdatedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotBlank
    private String organisationId;

    @NotNull
    @Size(min = 1)
    private Set<ReportStatusUpdate> statusUpdates;

    public Map<String, ReportStatusUpdate> statusUpdatesMap() {
        return statusUpdates.stream().collect(Collectors.toMap(ReportStatusUpdate::getTxId, Function.identity()));
    }

}
