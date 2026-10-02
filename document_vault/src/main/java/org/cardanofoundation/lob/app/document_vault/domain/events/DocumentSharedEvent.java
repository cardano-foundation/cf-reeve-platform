package org.cardanofoundation.lob.app.document_vault.domain.events;

import java.util.Set;

import org.jmolecules.event.annotation.DomainEvent;

@DomainEvent
public record DocumentSharedEvent(String documentId, String organisationId, Set<String> recipientAccountIds) {
}
