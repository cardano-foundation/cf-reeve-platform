package org.cardanofoundation.lob.app.blockchain_common.domain;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Single, common ledger-update event published by the blockchain publisher back to the originating module
 * (accounting core, reporting, funding). Replaces the per-module {@code TxsLedgerUpdatedEvent} /
 * {@code ReportsLedgerUpdatedEvent} / {@code SpendingEventsLedgerUpdatedEvent}. Lives in {@code blockchain_common}
 * so the publisher and all consumers can share it without cross-module (circular) dependencies.
 *
 * <p>Consumers filter on {@link #type} and apply only the updates relevant to them.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
public final class LedgerUpdatedEvent {

    public static final String VERSION = "1.0";

    private String organisationId;

    private LedgerUpdateType type;

    private Set<LedgerStatusUpdate> statusUpdates;

    public Map<String, LedgerStatusUpdate> statusUpdatesMap() {
        return statusUpdates.stream().collect(Collectors.toMap(LedgerStatusUpdate::getId, Function.identity()));
    }

}
