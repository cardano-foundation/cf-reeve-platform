package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * The commitment is what a KERI wallet signs, so its CBOR encoding is a wire format: any change to the
 * field set or their order invalidates every attestation already anchored on-chain.
 */
class DocumentAttestationCommitmentTest {

    private static final String HASH_A = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";
    private static final String HASH_B = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4";
    private static final String ENVELOPE_SHA = "9".repeat(64);

    private static DocumentPublishCommand fixture() {
        return new DocumentPublishCommand(
                "org-1", "doc-1", 1, "a".repeat(64), "b".repeat(64), "c".repeat(24), "Y2lwaGVydGV4dA==",
                List.of(
                        new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96), HASH_A),
                        new DocumentPublishCommand.PublishSlot("f".repeat(64), "0".repeat(96), HASH_B)),
                null);
    }

    @Test
    void carriesExactlyTheOfflineComputableIdentityFields() {
        MetadataMap map = DocumentAttestationCommitment.toMetadataMap(fixture(), ENVELOPE_SHA);

        Set<String> keys = ((List<?>) map.keys()).stream().map(Object::toString).collect(Collectors.toSet());
        // ipfs_cid and creation_slot are deliberately ABSENT: the attesting pod has neither IPFS nor a
        // chain tip, which is the entire reason this type exists instead of digesting the 1447 manifest.
        assertThat(keys).containsExactlyInAnyOrder(
                "v", "type", "org_id", "doc_id", "envelope_version",
                "content_hash", "plaintext_hash", "envelope_sha256", "slot_count", "recipient_key_hashes");
        assertThat(keys).doesNotContain("ipfs_cid", "creation_slot");
    }

    @Test
    void bindsTheEnvelopeAndEveryRecipientInSlotOrder() {
        MetadataMap map = DocumentAttestationCommitment.toMetadataMap(fixture(), ENVELOPE_SHA);

        assertThat(map.get("envelope_sha256")).isEqualTo(ENVELOPE_SHA);
        assertThat(map.get("content_hash")).isEqualTo("a".repeat(64));
        assertThat(map.get("plaintext_hash")).isEqualTo("b".repeat(64));

        MetadataList hashes = (MetadataList) map.get("recipient_key_hashes");
        assertThat(hashes.size()).isEqualTo(2);
        assertThat(hashes.getValueAt(0)).isEqualTo(HASH_A);
        assertThat(hashes.getValueAt(1)).isEqualTo(HASH_B);
    }

    /**
     * Determinism is what makes the digest reproducible by a third-party verifier: the same inputs must
     * always yield the same bytes, or an independently recomputed digest would not match the KEL.
     */
    @Test
    void serialisesDeterministically() throws Exception {
        String first = hex(DocumentAttestationCommitment.toMetadataMap(fixture(), ENVELOPE_SHA));
        String second = hex(DocumentAttestationCommitment.toMetadataMap(fixture(), ENVELOPE_SHA));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aDifferentRecipientChangesTheCommitment() throws Exception {
        // Substituting a recipient must break the attestation, otherwise the wallet's signature would
        // not actually bind who the document is addressed to.
        DocumentPublishCommand tampered = new DocumentPublishCommand(
                "org-1", "doc-1", 1, "a".repeat(64), "b".repeat(64), "c".repeat(24), "Y2lwaGVydGV4dA==",
                List.of(
                        new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96), HASH_A),
                        new DocumentPublishCommand.PublishSlot("f".repeat(64), "0".repeat(96), "1".repeat(64))),
                null);

        assertThat(hex(DocumentAttestationCommitment.toMetadataMap(tampered, ENVELOPE_SHA)))
                .isNotEqualTo(hex(DocumentAttestationCommitment.toMetadataMap(fixture(), ENVELOPE_SHA)));
    }

    @Test
    void aDifferentEnvelopeChangesTheCommitment() throws Exception {
        assertThat(hex(DocumentAttestationCommitment.toMetadataMap(fixture(), "8".repeat(64))))
                .isNotEqualTo(hex(DocumentAttestationCommitment.toMetadataMap(fixture(), ENVELOPE_SHA)));
    }

    private static String hex(MetadataMap map) throws Exception {
        return HexFormat.of().formatHex(CborSerializationUtil.serialize(map.getMap()));
    }
}
