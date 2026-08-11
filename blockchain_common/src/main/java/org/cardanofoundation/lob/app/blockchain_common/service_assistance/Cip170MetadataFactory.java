package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.args.RawArgs;
import org.cardanofoundation.signify.cesr.util.CoreUtil;

/**
 * Builds the CIP-170 label-170 {@code ATTEST} and {@code AUTH_BEGIN} metadata maps, matching the
 * field names and chunking used by other label-170 publishers on this chain.
 *
 * <p>Byte identity with those publishers comes from matching their exact key/value set, not from
 * insertion order: {@link CborSerializationUtil} emits canonical CBOR, which sorts map keys. The
 * {@code put()} calls below still follow a fixed order so the wire shape reads top to bottom.
 *
 * <p>Pure and stateless — every method is a deterministic function of its arguments.
 */
public class Cip170MetadataFactory {

    /** Chunk size (bytes) for {@link #authBeginMap}'s {@code c} field. */
    static final int CHAIN_CHUNK_SIZE = 64;

    /**
     * Builds the label-170 {@code ATTEST} map: {@code t="ATTEST"}, {@code s=kelSequence},
     * {@code i=aid}, {@code d=digestQb64}, {@code v={v:"1.0"}}.
     */
    public MetadataMap attestMap(String aid, String digestQb64, String kelSequence) {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("t", "ATTEST");
        map.put("s", kelSequence);
        map.put("i", aid);
        map.put("d", digestQb64);

        MetadataMap v = MetadataBuilder.createMap();
        v.put("v", "1.0");
        map.put("v", v);

        return map;
    }

    /**
     * Builds the label-170 {@code AUTH_BEGIN} map: {@code t="AUTH_BEGIN"}, {@code s=leafSchemaSaid},
     * {@code i=aid}, {@code c}=64-byte chunks of {@code reducedCesrChain} (last chunk shorter, or
     * an empty list if the chain is empty), {@code v={v:"1.0",k:"KERI10JSON",a:"ACDC10JSON"}}, and
     * {@code m={l:[...authorizedLabels],...optionalM}}.
     *
     * <p>{@code optionalM} may be {@code null} or empty; its values are written as-is if already a
     * {@link String}, otherwise via {@link String#valueOf(Object)}. The key {@code "l"} is reserved
     * for {@code authorizedLabels} and rejected in {@code optionalM}, since a repeated key would
     * overwrite the authorization-labels list rather than add to it.
     */
    public MetadataMap authBeginMap(String aid, String leafSchemaSaid, byte[] reducedCesrChain,
            Map<String, Object> optionalM, List<Long> authorizedLabels) {
        MetadataMap map = MetadataBuilder.createMap();
        map.put("t", "AUTH_BEGIN");
        map.put("s", leafSchemaSaid);
        map.put("i", aid);
        map.put("c", chunk(reducedCesrChain));

        MetadataMap v = MetadataBuilder.createMap();
        v.put("v", "1.0");
        v.put("k", "KERI10JSON");
        v.put("a", "ACDC10JSON");
        map.put("v", v);

        map.put("m", authorizationMap(optionalM, authorizedLabels));

        return map;
    }

    /**
     * The Blake3-256 {@link Diger} qb64 digest of
     * {@code CborSerializationUtil.serialize(map1447.getMap())} — the idiom used to seal a reeve
     * metadata map into an interaction event before anchoring it. Always starts with {@code "E"},
     * the CESR code for Blake3-256.
     */
    public String digestOf(MetadataMap map1447) {
        try {
            byte[] blake3_256 = CoreUtil.blake3_256(CborSerializationUtil.serialize(map1447.getMap()), 32);
            return new Diger(RawArgs.builder().raw(blake3_256).build()).getQb64();
        } catch (CborException e) {
            throw new IllegalStateException("Failed to CBOR-serialize a metadata map for digesting.", e);
        }
        // Digest failures raise unchecked now and are left to propagate: a Blake3 failure here is not
        // something a caller can act on.
    }

    // --- internals ---

    private static MetadataMap authorizationMap(Map<String, Object> optionalM, List<Long> authorizedLabels) {
        Objects.requireNonNull(authorizedLabels, "authorizedLabels must not be null");

        MetadataMap m = MetadataBuilder.createMap();
        MetadataList l = MetadataBuilder.createList();
        for (Long label : authorizedLabels) {
            l.add(BigInteger.valueOf(label));
        }
        m.put("l", l);
        if (optionalM != null) {
            for (Map.Entry<String, Object> entry : optionalM.entrySet()) {
                if ("l".equals(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "optionalM must not contain the reserved key \"l\" -- it would silently"
                                    + " overwrite the authorized-labels list built from authorizedLabels.");
                }
                Object value = entry.getValue();
                m.put(entry.getKey(), value instanceof String stringValue ? stringValue : String.valueOf(value));
            }
        }
        return m;
    }

    /** Ceiling division into {@link #CHAIN_CHUNK_SIZE}-byte chunks, last one shorter; an empty
     *  {@code data} array yields an empty list rather than a single empty chunk. */
    private static MetadataList chunk(byte[] data) {
        MetadataList chunks = MetadataBuilder.createList();
        int numChunks = (data.length + CHAIN_CHUNK_SIZE - 1) / CHAIN_CHUNK_SIZE;
        for (int i = 0; i < numChunks; i++) {
            int start = i * CHAIN_CHUNK_SIZE;
            int end = Math.min(start + CHAIN_CHUNK_SIZE, data.length);
            chunks.add(Arrays.copyOfRange(data, start, end));
        }
        return chunks;
    }
}
