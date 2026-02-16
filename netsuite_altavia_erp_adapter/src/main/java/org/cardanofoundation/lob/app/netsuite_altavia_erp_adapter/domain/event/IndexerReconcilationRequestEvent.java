package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.event;

import java.time.LocalDate;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@Builder
@Getter
@ToString
public class IndexerReconcilationRequestEvent {

    public static final int VERSION = 1;

    private final EventMetadata metadata;
    private final String reconciliationId;
    private final String organisationId;
    private final LocalDate from;
    private final LocalDate to;
}
