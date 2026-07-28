package org.cardanofoundation.lob.app.blockchain_common.domain.events;

import java.util.List;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Publish request handed to blockchain_publisher. PII-FREE BY DESIGN (spec B5 #3): the IPFS document
 * and L1 metadata are generated exclusively from these fields, so nothing here may ever carry e-mails,
 * recipient labels, key ids, file names, descriptions, or account ids. Enforced by tests in Task 12.
 *
 * <p>{@link PublishSlot#recipientKeyHash} is the single deliberate exception, and it is exempted BY NAME
 * in {@code DocumentPublishCommandPiiTest} and both {@code NoPiiOnDocumentPublishPathArchTest}s rather
 * than renamed to slip past their pattern. It is a SHA-256 digest of a public key — publishable in the
 * same sense {@code organisationId} is — and it exists so the public Indexer can filter documents by
 * recipient. It DOES make a published document permanently linkable to its recipients; see
 * docs/onChainFormat.md "Recipient key hashes".
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

    /**
     * @param recipientKeyHash sha256 of the recipient's X25519 public key, lowercase hex. Exported to
     *                         L1 but NOT to the IPFS envelope. Order-significant: the manifest's
     *                         {@code recipient_key_hashes[i]} lines up with {@code slots[i]}.
     */
    public record PublishSlot(String ephemeralPub, String wrappedDek, String recipientKeyHash) {
    }
}
