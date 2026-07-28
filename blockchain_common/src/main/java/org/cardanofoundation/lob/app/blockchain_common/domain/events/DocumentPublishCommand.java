package org.cardanofoundation.lob.app.blockchain_common.domain.events;

import java.util.List;

import org.jmolecules.event.annotation.DomainEvent;

/**
 * Publish request handed to blockchain_publisher.
 *
 * <p>PII-free by design: the IPFS document and the L1 metadata are generated exclusively from these
 * fields, so nothing here may carry e-mails, recipient labels, key ids, file names, descriptions or
 * account ids. {@link PublishSlot#recipientKeyHash} is the single deliberate exception — a SHA-256
 * digest of a public key, published so the Indexer can filter documents by recipient. It makes a
 * published document permanently linkable to its recipients; see docs/onChainFormat.md
 * "Recipient key hashes".
 *
 * @param attestationCeremonyId KERI wallet-attestation ceremony consumed by an attested publish, or
 *                              null for a plain publish. Carried on the command so the binding
 *                              survives a retry-sweep re-emission.
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
                                     String attestationCeremonyId,
                                     ConsumedAttestationRef attestation) {

    /** Convenience factory for a plain (unattested) publish. */
    public static DocumentPublishCommand plain(String organisationId, String documentId, int envelopeVersion,
                                               String contentHash, String plaintextHash, String payloadNonce,
                                               String ciphertextBase64, List<PublishSlot> slots) {
        return new DocumentPublishCommand(organisationId, documentId, envelopeVersion, contentHash,
                plaintextHash, payloadNonce, ciphertextBase64, slots, null, null);
    }

    /**
     * The consumed wallet attestation, carried on the command rather than looked up by the publisher,
     * so the publisher needs no access to {@code keri_attestation} or {@code document_vault} — they
     * may run in a different process.
     *
     * @param aid         the wallet AID that attested.
     * @param payloadSaid SAID of the saidified remotesign payload the wallet's KEL anchors. This, not
     *                    the commitment digest, becomes the on-chain {@code 170.d}.
     * @param kelSequence KEL coordinates of the anchoring event.
     */
    public record ConsumedAttestationRef(String aid, String payloadSaid, String kelSequence) {
    }

    /**
     * @param recipientKeyHash sha256 of the recipient's X25519 public key, lowercase hex. Exported to
     *                         L1 but NOT to the IPFS envelope. Order-significant: the manifest's
     *                         {@code recipient_key_hashes[i]} lines up with {@code slots[i]}.
     */
    public record PublishSlot(String ephemeralPub, String wrappedDek, String recipientKeyHash) {
    }
}
