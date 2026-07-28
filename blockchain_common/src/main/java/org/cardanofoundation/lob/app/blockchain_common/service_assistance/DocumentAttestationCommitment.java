package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * The canonical payload a KERI wallet attests when authorising a document publish.
 *
 * <p>The wallet attests this rather than the finished DOCUMENT manifest, because the attestation
 * ceremony runs in the user-facing tier, which has neither IPFS credentials nor chain access. The
 * manifest's {@code ipfs_cid} needs a pin and its {@code creation_slot} needs a chain tip; both are
 * the publisher tier's job and are filled in after the wallet has signed.
 *
 * <p>Leaving those two out costs nothing: fetching the CID yields bytes whose SHA-256 must equal the
 * attested {@code envelope_sha256}, so content cannot be substituted. What is bound covers the whole
 * document — the envelope bytes, both content commitments, the organisation and document ids, and the
 * recipient set, so recipients cannot be added or swapped after attestation. A verifier recomputes
 * this map from the on-chain manifest plus the fetched envelope and checks the wallet's KEL anchored
 * its digest.
 *
 * <p>Key insertion order is part of the wire format: {@link MetadataMap} preserves it and the digest
 * is taken over the serialised CBOR, so reordering these puts invalidates every existing attestation.
 * Append new fields at the end only, and bump {@link #VERSION}.
 */
public final class DocumentAttestationCommitment {

    public static final String VERSION = "1.0";
    public static final String TYPE = "DOCUMENT";

    private DocumentAttestationCommitment() {
    }

    /**
     * @param envelopeSha256 SHA-256 (lowercase hex) of the exact envelope JSON produced by
     *                       {@link DocumentIpfsSerialiser#serialise(DocumentPublishCommand)} — the
     *                       bytes the publisher will pin verbatim.
     */
    public static MetadataMap toMetadataMap(DocumentPublishCommand command, String envelopeSha256) {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("v", VERSION);
        map.put("type", TYPE);
        map.put("org_id", command.organisationId());
        map.put("doc_id", command.documentId());
        map.put("envelope_version", BigInteger.valueOf(command.envelopeVersion()));
        map.put("content_hash", command.contentHash());
        map.put("plaintext_hash", command.plaintextHash());
        map.put("envelope_sha256", envelopeSha256);
        map.put("slot_count", BigInteger.valueOf(command.slots().size()));

        // Same order as the manifest's recipient_key_hashes and the envelope's slots; never sorted.
        MetadataList recipientKeyHashes = MetadataBuilder.createList();
        command.slots().forEach(slot -> recipientKeyHashes.add(slot.recipientKeyHash()));
        map.put("recipient_key_hashes", recipientKeyHashes);

        return map;
    }
}
