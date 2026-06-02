package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction;

import java.util.Set;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.jmolecules.event.annotation.DomainEvent;

@AllArgsConstructor
@Builder
@Getter
@DomainEvent
@NoArgsConstructor
public class TransactionBatchChunkCommittedEvent {

    @NotNull
    private String batchId;

    @NotNull
    private Integer processedTransactionCount;

    @Builder.Default
    private Set<String> batchesToBeUpdated = Set.of();

}
