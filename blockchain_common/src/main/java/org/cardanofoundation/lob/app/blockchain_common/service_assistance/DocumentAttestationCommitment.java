package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * The canonical payload a KERI wallet attests when authorising a document publish.
 *
 * <h2>Why this exists rather than digesting the finished 1447 manifest</h2>
 *
 * The attestation ceremony runs in the USER-FACING tier (document_vault + keri_attestation), which in
 * the split deployment is a pod with no IPFS credentials and no chain access at all — see the {@code
 * api} service in cf-reeve-application's docker-compose.yml, where {@code
 * LOB_BLOCKCHAIN_PUBLISHER_ENABLED} and {@code LOB_BLOCKCHAIN_READER_ENABLED} are both false. The
 * finished manifest cannot be built there: {@code data.ipfs_cid} requires pinning to IPFS and
 * {@code metadata.creation_slot} requires a chain tip, and both are the hardened publisher tier's job.
 *
 * <p>So the wallet attests THIS instead: every field that identifies the document, and nothing that
 * depends on the network. The publisher pins, reads the tip and assembles the manifest afterwards.
 *
 * <h2>What it still binds</h2>
 *
 * <ul>
 *   <li>{@code envelope_sha256} — the exact IPFS envelope bytes, so ciphertext, nonce and every
 *       recipient slot are covered as one unit.</li>
 *   <li>{@code content_hash} / {@code plaintext_hash} — the ciphertext and the plaintext commitment,
 *       both of which also appear in the on-chain manifest.</li>
 *   <li>{@code slot_count} / {@code recipient_key_hashes} — who the document is addressed to, also
 *       on-chain, so recipients cannot be added or swapped after attestation.</li>
 *   <li>{@code org_id} / {@code doc_id} — which organisation published what.</li>
 * </ul>
 *
 * <p>A third party verifying an anchored document recomputes this from the on-chain manifest plus the
 * fetched envelope, and checks the wallet's KEL anchored its digest. The two fields deliberately NOT
 * covered — {@code ipfs_cid} and {@code creation_slot} — cannot be used to substitute content:
 * fetching the CID yields bytes whose SHA-256 must equal {@code envelope_sha256}, and whose ciphertext
 * must hash to the attested {@code content_hash}.
 *
 * <p>Key insertion order IS the wire format: {@link MetadataMap} preserves it and the digest is taken
 * over the serialised CBOR, so reordering these puts would silently invalidate every existing
 * attestation. Add new fields at the END only, and bump {@link #VERSION}.
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
