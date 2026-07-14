package org.cardanofoundation.lob.app.document_vault.domain.events;

import java.util.List;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Publish request handed to blockchain_publisher. PII-FREE BY DESIGN (spec B5 #3): the IPFS document
 * and L1 metadata are generated exclusively from these fields, so nothing here may ever carry e-mails,
 * recipient labels, key ids, file names, descriptions, or account ids. Enforced by tests in Task 12.
 */
@DomainEvent
public record DocumentPublishCommand(String organisationId,
                                     String documentId,
                                     int envelopeVersion,
                                     String contentHash,
                                     String plaintextHash,
                                     String payloadNonce,
                                     String ciphertextBase64,
                                     List<PublishSlot> slots) {

    public record PublishSlot(String ephemeralPub, String wrappedDek) {
    }
}
