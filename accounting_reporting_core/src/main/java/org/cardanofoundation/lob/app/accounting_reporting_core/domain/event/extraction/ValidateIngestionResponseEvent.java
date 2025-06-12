package org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.jmolecules.event.annotation.DomainEvent;
import org.zalando.problem.Problem;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@DomainEvent
@Builder
public class ValidateIngestionResponseEvent {

    private String correlationId;
    private Boolean valid;
    private List<Problem> errors;

}
