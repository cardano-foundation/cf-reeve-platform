package org.cardanofoundation.lob.app.document_vault.domain.events;

import java.util.Set;

import org.jmolecules.event.annotation.DomainEvent;

/** Fired when a published document reaches FINALIZED on-chain. Metadata-minimized (no content, no e-mails). */
@DomainEvent
public record DocumentPublishedEvent(String documentId, String organisationId, Set<String> recipientAccountIds) {
}
