package org.cardanofoundation.lob.app.funding.domain.events;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.jmolecules.event.annotation.DomainEvent;

import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@DomainEvent
public class SpendingEventsPublishCommand {

    public static final String VERSION = "1.0";
    @NotBlank
    private String organisationId;

    @NotNull
    @Size(min = 1)
    private Set<SpendingEventPublishView> spendingEvents;
}
