package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.args.RawArgs;
import org.cardanofoundation.signify.cesr.util.CoreUtil;

/**
 * Golden-vector tests for {@link Cip170MetadataFactory}: pins the exact field names, insertion
 * order and shapes of the label-170 {@code ATTEST} and {@code AUTH_BEGIN} metadata maps (including
 * chunking) this factory must produce to be a valid on-chain anchor.
 */
class Cip170MetadataFactoryTest {

    private final Cip170MetadataFactory factory = new Cip170MetadataFactory();

    private static final String AID = "EAID_9x8y7z6w5v4u3t2s1r0q_ABCDEFGHIJK";
    private static final String DIGEST = "EDIGEST_1234567890abcdefghijklmnop";
    private static final String KEL_SEQUENCE = "3";
    private static final String LEAF_SCHEMA_SAID = "ESCHEMA_1234567890abcdefghijklmno";

    // --- attestMap: exact field set/order ---

    @Test
    void attestMapHasExactFieldsFromReference() {
        MetadataMap map = factory.attestMap(AID, DIGEST, KEL_SEQUENCE);

        assertEquals("ATTEST", map.get("t"));
        assertEquals(KEL_SEQUENCE, map.get("s"));
        assertEquals(AID, map.get("i"));
        assertEquals(DIGEST, map.get("d"));

        MetadataMap v = (MetadataMap) map.get("v");
        assertEquals("1.0", v.get("v"));
        assertEquals(1, v.keys().size());
    }

    @Test
    void attestMapProducesTheSameCanonicalCborBytesAsTheReferenceKeySet() throws Exception {
        // NOTE: CborSerializationUtil.serialize(DataItem) defaults to canonical=true (RFC 7049
        // Section 3.9), which sorts map keys deterministically regardless of put() insertion order --
        // so this test cannot and does not catch a reordering of attestMap's puts (insertion order
        // does not affect the serialized bytes at all here). What it DOES pin: the exact key/value
        // SET matches the expected shape byte-for-byte -- an extra, missing, renamed, or wrong-type/
        // wrong-value key would change the canonical CBOR bytes and fail this test, even though the
        // get()-based assertions above wouldn't necessarily catch every such change (e.g. an extra
        // key with no corresponding get() assertion).
        MetadataMap reference = MetadataBuilder.createMap();
        reference.put("t", "ATTEST");
        reference.put("s", KEL_SEQUENCE);
        reference.put("i", AID);
        reference.put("d", DIGEST);
        MetadataMap referenceV = MetadataBuilder.createMap();
        referenceV.put("v", "1.0");
        reference.put("v", referenceV);

        MetadataMap map = factory.attestMap(AID, DIGEST, KEL_SEQUENCE);

        assertArrayEquals(CborSerializationUtil.serialize(reference.getMap()), CborSerializationUtil.serialize(map.getMap()));
    }

    // --- authBeginMap: exact field set/order ---

    @Test
    void authBeginMapHasExactFieldsFromReferenceWithOptionalMEntries() {
        byte[] chain = sequentialBytes(150);
        Map<String, Object> optionalM = new LinkedHashMap<>();
        optionalM.put("LEI", "5299000WN3W1WHOZL256");
        optionalM.put("epoch", 42L); // non-String value must be toString'd

        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, chain, optionalM, List.of(1447L));

        assertEquals("AUTH_BEGIN", map.get("t"));
        assertEquals(LEAF_SCHEMA_SAID, map.get("s"));
        assertEquals(AID, map.get("i"));

        MetadataList c = (MetadataList) map.get("c");
        assertChunkReassemblyEquals(chain, c);

        MetadataMap v = (MetadataMap) map.get("v");
        assertEquals("1.0", v.get("v"));
        assertEquals("KERI10JSON", v.get("k"));
        assertEquals("ACDC10JSON", v.get("a"));

        MetadataMap m = (MetadataMap) map.get("m");
        MetadataList l = (MetadataList) m.get("l");
        assertEquals(1, l.size());
        assertEquals(BigInteger.valueOf(1447), l.getValueAt(0));
        assertEquals("5299000WN3W1WHOZL256", m.get("LEI"));
        assertEquals("42", m.get("epoch"));
    }

    @Test
    void authBeginMapProducesTheSameCanonicalCborBytesAsTheReferenceKeySet() throws Exception {
        // Same rationale/caveat as the attestMap canonical-CBOR test above: canonical CBOR (default
        // for CborSerializationUtil.serialize) sorts map keys, so this does NOT catch a reordering of
        // authBeginMap's puts. It reproduces the expected key/value SET verbatim (single "LEI" entry,
        // single authorized label 1447) and pins the exact serialized bytes -- an extra/missing/
        // wrong-value key would fail this test.
        byte[] chain = sequentialBytes(70);
        String lei = "5299000WN3W1WHOZL256";

        MetadataMap reference = MetadataBuilder.createMap();
        reference.put("t", "AUTH_BEGIN");
        reference.put("s", LEAF_SCHEMA_SAID);
        reference.put("i", AID);
        MetadataList referenceChunks = MetadataBuilder.createList();
        for (byte[] chunk : referenceSplitIntoChunks(chain, 64)) {
            referenceChunks.add(chunk);
        }
        reference.put("c", referenceChunks);
        MetadataMap referenceV = MetadataBuilder.createMap();
        referenceV.put("v", "1.0");
        referenceV.put("k", "KERI10JSON");
        referenceV.put("a", "ACDC10JSON");
        reference.put("v", referenceV);
        MetadataMap referenceM = MetadataBuilder.createMap();
        MetadataList referenceL = MetadataBuilder.createList();
        referenceL.add(BigInteger.valueOf(1447));
        referenceM.put("l", referenceL);
        referenceM.put("LEI", lei);
        reference.put("m", referenceM);

        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, chain,
                new LinkedHashMap<>(Map.of("LEI", lei)), List.of(1447L));

        assertArrayEquals(CborSerializationUtil.serialize(reference.getMap()), CborSerializationUtil.serialize(map.getMap()));
    }

    @Test
    void authBeginMapAcceptsMultipleAuthorizedLabelsInOrder() {
        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, new byte[0], null, List.of(1447L, 9999L));

        MetadataMap m = (MetadataMap) map.get("m");
        MetadataList l = (MetadataList) m.get("l");
        assertEquals(2, l.size());
        assertEquals(BigInteger.valueOf(1447), l.getValueAt(0));
        assertEquals(BigInteger.valueOf(9999), l.getValueAt(1));
    }

    @Test
    void authBeginMapWithNullOptionalMStillHasLLabelOnly() {
        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, new byte[0], null, List.of(1447L));

        MetadataMap m = (MetadataMap) map.get("m");
        MetadataList l = (MetadataList) m.get("l");
        assertEquals(1, l.size());
        assertEquals(BigInteger.valueOf(1447), l.getValueAt(0));
    }

    // --- optionalM must never be able to corrupt m.l: "l" is a reserved key ---

    @Test
    void authBeginMapRejectsAnOptionalMEntryThatWouldClobberTheReservedLKey() {
        // The underlying co.nstant.in.cbor Map treats repeated keys as same-slot overwrites, so an
        // optionalM entry keyed "l" would otherwise silently replace the authorized-labels
        // MetadataList built from authorizedLabels with a plain string -- corrupting the on-chain
        // authorization-labels field. authBeginMap must reject this outright (fail fast) rather than
        // ever construct a map where m.get("l") is anything but the MetadataList of authorizedLabels.
        Map<String, Object> optionalM = new LinkedHashMap<>();
        optionalM.put("l", "attacker-controlled value");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> factory.authBeginMap(AID, LEAF_SCHEMA_SAID, new byte[0], optionalM, List.of(1447L)));
        assertTrue(thrown.getMessage().contains("\"l\""));
    }

    @Test
    void authBeginMapRejectsNullAuthorizedLabels() {
        assertThrows(NullPointerException.class,
                () -> factory.authBeginMap(AID, LEAF_SCHEMA_SAID, new byte[0], null, null));
    }

    // --- chunking edge cases: empty chain, exactly 64 bytes, 65 bytes ---

    @Test
    void emptyChainProducesZeroChunks() {
        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, new byte[0], null, List.of(1447L));

        MetadataList c = (MetadataList) map.get("c");
        assertEquals(0, c.size());
    }

    @Test
    void exactly64ByteChainProducesOneFullChunk() {
        byte[] chain = sequentialBytes(64);
        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, chain, null, List.of(1447L));

        MetadataList c = (MetadataList) map.get("c");
        assertEquals(1, c.size());
        assertArrayEquals(chain, (byte[]) c.getValueAt(0));
    }

    @Test
    void sixtyFiveByteChainProducesTwoChunksWithShorterLastOne() {
        byte[] chain = sequentialBytes(65);
        MetadataMap map = factory.authBeginMap(AID, LEAF_SCHEMA_SAID, chain, null, List.of(1447L));

        MetadataList c = (MetadataList) map.get("c");
        assertEquals(2, c.size());
        assertEquals(64, ((byte[]) c.getValueAt(0)).length);
        assertEquals(1, ((byte[]) c.getValueAt(1)).length);
        assertChunkReassemblyEquals(chain, c);
    }

    // --- digestOf ---

    @Test
    void digestOfMatchesTheTwoArgDigerIdiomAndStartsWithE() throws Exception {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("foo", "bar");
        map.put("count", BigInteger.valueOf(7));

        String expected = new Diger(new RawArgs(), CborSerializationUtil.serialize(map.getMap())).getQb64();

        String actual = factory.digestOf(map);

        assertEquals(expected, actual);
        assertTrue(actual.startsWith("E"));
    }

    @Test
    void digestOfIsEquivalentToTheLegacyComputeThenWrapIdiom() throws Exception {
        // Pins the choice of Diger idiom by evidence: the two-arg constructor (new Diger(new
        // RawArgs(), bytes)) computes the digest directly, while the legacy blockchain_publisher
        // KeriService instead pre-computes blake3_256 and wraps the already-computed digest via
        // RawArgs.builder().raw(...).build(). Both idioms must agree for digestOf's two-arg choice
        // to be safe.
        MetadataMap map = MetadataBuilder.createMap();
        map.put("foo", "bar");
        map.put("count", BigInteger.valueOf(7));

        byte[] cbor = CborSerializationUtil.serialize(map.getMap());
        byte[] blake3 = CoreUtil.blake3_256(cbor, 32);
        String legacy = new Diger(RawArgs.builder().raw(blake3).build()).getQb64();

        assertEquals(legacy, factory.digestOf(map));
    }

    // --- helpers ---

    private static void assertChunkReassemblyEquals(byte[] expected, MetadataList chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < chunks.size(); i++) {
            out.writeBytes((byte[]) chunks.getValueAt(i));
        }
        assertArrayEquals(expected, out.toByteArray());
    }

    private static byte[] sequentialBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    /** Independent reimplementation of the chunking loop, kept separate from
     *  {@code Cip170MetadataFactory}'s own chunking so the canonical-CBOR golden test above is not
     *  just checking the factory against itself. */
    private static byte[][] referenceSplitIntoChunks(byte[] data, int chunkSize) {
        int numChunks = (data.length + chunkSize - 1) / chunkSize;
        byte[][] chunks = new byte[numChunks][];
        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            chunks[i] = Arrays.copyOfRange(data, start, end);
        }
        return chunks;
    }
}
