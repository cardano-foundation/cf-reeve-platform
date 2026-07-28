package org.cardanofoundation.lob.app.blockchain_common.domain.events;

import java.util.List;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Publish request handed to blockchain_publisher. PII-FREE BY DESIGN (spec B5 #3): the IPFS document
 * and L1 metadata are generated exclusively from these fields, so nothing here may ever carry e-mails,
 * recipient labels, key ids, file names, descriptions, or account ids. Enforced by tests in Task 12.
 *
 * @param attestationCeremonyId The KERI wallet-attestation ceremony consumed by an attested publish
 *                              (design §5.1, Task 14) — null for a plain publish, which remains the
 *                              default. Carried into blockchain_publisher's dispatch record so the
 *                              binding survives a retry-sweep re-emission (same static factory,
 *                              {@code VaultDocumentService#toPublishCommand}).
 */
@DomainEvent
public record DocumentPublishCommand(String organisationId,
                                     String documentId,
                                     int envelopeVersion,
                                     String contentHash,
                                     String plaintextHash,
                                     String payloadNonce,
                                     String ciphertextBase64,
                                     List<PublishSlot> slots,
                                     String attestationCeremonyId) {

    public record PublishSlot(String ephemeralPub, String wrappedDek) {
    }
}
