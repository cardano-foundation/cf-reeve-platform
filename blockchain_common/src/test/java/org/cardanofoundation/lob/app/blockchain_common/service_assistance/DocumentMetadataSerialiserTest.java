package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Asserts the 1447 DOCUMENT manifest shape. The data section must
 * carry exactly the six fields below — nothing else — so any accidental addition of a PII-capable field is
 * caught here.
 *
 * <p>Organisation-not-found handling moved out of this class with WS3 step 1 (org resolution is now the
 * CALLER's responsibility - {@code blockchain_common} must not depend on {@code organisation}), so that
 * behaviour is covered where it now lives: {@code DocumentL1TransactionCreatorTest} /
 * {@code DocumentAttestationTargetProviderTest} in {@code blockchain_publisher}.
 */
class DocumentMetadataSerialiserTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:15:30Z"), ZoneId.of("UTC"));
    private static final long CREATION_SLOT = 123456L;
    private static final String HASH_A = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";
    private static final String HASH_B = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4";

    private final DocumentMetadataSerialiser serialiser = new DocumentMetadataSerialiser(FIXED_CLOCK);

    private static DocumentPublishCommand fixture() {
        return new DocumentPublishCommand(
                "org-1",
                "doc-1",
                1,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(24),
                "Y2lwaGVydGV4dA==",
                List.of(
                        new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96), HASH_A),
                        new DocumentPublishCommand.PublishSlot("f".repeat(64), "0".repeat(96), HASH_B)),
                null, null);
    }

    @Test
    void serialisesTheNormativeDocumentManifest() {
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(), "bafy-cid-1", CREATION_SLOT,
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        assertThat(metadataMap.get("type")).isEqualTo("DOCUMENT");
        assertThat(metadataMap.get("metadata")).isNotNull();
        assertThat(metadataMap.get("org")).isNotNull();

        MetadataMap org = (MetadataMap) metadataMap.get("org");
        assertThat(org.get("id")).isEqualTo("org-1");
        assertThat(org.get("name")).isEqualTo("Acme");

        MetadataMap metadata = (MetadataMap) metadataMap.get("metadata");
        assertThat(metadata.get("creation_slot")).isEqualTo(BigInteger.valueOf(CREATION_SLOT));
        assertThat(metadata.get("version")).isEqualTo(DocumentMetadataSerialiser.VERSION);

        MetadataMap data = (MetadataMap) metadataMap.get("data");
        assertThat(data.get("id")).isEqualTo("doc-1");
        assertThat(data.get("ipfs_cid")).isEqualTo("bafy-cid-1");
        assertThat(data.get("content_hash")).isEqualTo("a".repeat(64));
        assertThat(data.get("plaintext_hash")).isEqualTo("b".repeat(64));
        assertThat(data.get("envelope_version")).isEqualTo(BigInteger.valueOf(1));
        assertThat(data.get("slot_count")).isEqualTo(BigInteger.valueOf(2));

        // nothing else may be present in the data section — recipient_key_hashes is the ONE identifier
        // this format publishes, and any further addition must be a deliberate decision, not a leak.
        Set<String> dataKeys = ((List<?>) data.keys()).stream().map(Object::toString).collect(Collectors.toSet());
        assertThat(dataKeys).containsExactlyInAnyOrder(
                "id", "ipfs_cid", "content_hash", "plaintext_hash", "envelope_version", "slot_count",
                "recipient_key_hashes");
    }

    /**
     * Order is load-bearing: recipient_key_hashes[i] must line up with slots[i] in the IPFS envelope,
     * which is what lets a recipient address their own slot directly instead of trial-decrypting every
     * one. Sorting or deduplicating the list would break that silently.
     */
    @Test
    void emitsRecipientKeyHashesInSlotOrder() {
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(), "bafy-cid-1", CREATION_SLOT,
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        MetadataMap data = (MetadataMap) metadataMap.get("data");
        MetadataList hashes = (MetadataList) data.get("recipient_key_hashes");

        assertThat(hashes.size()).isEqualTo(2);
        assertThat(hashes.getValueAt(0)).isEqualTo(HASH_A);
        assertThat(hashes.getValueAt(1)).isEqualTo(HASH_B);
        assertThat(data.get("slot_count")).isEqualTo(BigInteger.valueOf(hashes.size()));
    }

    /**
     * The version bump is what tells a reader whether absent recipient_key_hashes means "this producer
     * predates the field" or "this manifest is malformed".
     */
    @Test
    void declaresMetadataVersion11() {
        assertThat(DocumentMetadataSerialiser.VERSION).isEqualTo("1.1");
    }

    /**
     * End-to-end guard that the serialiser output and the JSON schema agree: serialise a manifest exactly as
     * {@code DocumentL1TransactionCreator} does (CBOR -> JSON) and validate it against
     * {@code document_lob_blockchain_transaction_metadata_schema.json} with the production checker. Mirrors
     * {@code SpendingEventMetadataSerialiserTest#serialisedBundleValidatesAgainstSchema}.
     */
    @Test
    void serialisedManifestValidatesAgainstSchema() throws Exception {
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(),
                "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", CREATION_SLOT,
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        byte[] bytes = CborSerializationUtil.serialize(metadataMap.getMap());
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);

        JsonSchemaMetadataChecker checker = new JsonSchemaMetadataChecker(new ObjectMapper());
        checker.setMetadataSchemaResource(new ClassPathResource("document_lob_blockchain_transaction_metadata_schema.json"));
        checker.setEnableChecker(true);

        assertThat(checker.checkTransactionMetadata(json)).isTrue();
    }

    /**
     * Defense-in-depth: a manifest whose {@code data} section gains an extra field (e.g. an accidental PII leak)
     * must fail schema validation, since the schema declares exactly the six normative keys with
     * {@code additionalProperties: false}.
     */
    @Test
    void manifestWithExtraDataFieldFailsSchemaValidation() throws Exception {
        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(fixture(),
                "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi", CREATION_SLOT,
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        byte[] bytes = CborSerializationUtil.serialize(metadataMap.getMap());
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) objectMapper.readTree(json);
        ((ObjectNode) root.get("data")).put("unexpected_field", "should-not-be-here");
        String tamperedJson = objectMapper.writeValueAsString(root);

        JsonSchemaMetadataChecker checker = new JsonSchemaMetadataChecker(objectMapper);
        checker.setMetadataSchemaResource(new ClassPathResource("document_lob_blockchain_transaction_metadata_schema.json"));
        checker.setEnableChecker(true);

        assertThat(checker.checkTransactionMetadata(tamperedJson)).isFalse();
    }
}
