package org.cardanofoundation.lob.app.keri_attestation.service;

import java.math.BigInteger;
import java.security.DigestException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.args.RawArgs;

/**
 * Builds CIP-170 label-170 metadata maps byte-for-byte compatible with the in-repo reference
 * publishing scripts — same field names, same insertion order, same chunking — so anything this
 * factory produces is safe to anchor on-chain under metadata label 170:
 * {@code docs/keri/AttestTransaction.java} (ATTEST, lines 188-198) and
 * {@code docs/keri/advanced/PublishExistingCredential.java} (AUTH_BEGIN + chunking, lines
 * 219-286).
 *
 * <p>Pure and stateless: every method is a deterministic function of its arguments, with no
 * dependency on the rest of this module (no repository, no KERI agent, no clock).
 */
@Service
public class Cip170MetadataFactory {

    /** Chunk size (bytes) for {@link #authBeginMap}'s {@code c} field — identical to
     *  {@code PublishExistingCredential.splitIntoChunks(data, 64)}. */
    static final int CHAIN_CHUNK_SIZE = 64;

    /**
     * Builds the label-170 {@code ATTEST} map: {@code t="ATTEST"}, {@code s=kelSequence},
     * {@code i=aid}, {@code d=digestQb64}, {@code v={v:"1.0"}}. Field names, insertion order and
     * the {@code v} map shape match {@code AttestTransaction.buildTransaction} exactly.
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
     * an empty list if the chain is empty), {@code v={v:"1.0",k:"KERI10",a:"ACDC10"}}, and
     * {@code m={l:[...authorizedLabels],...optionalM}}. Field names, insertion order and the
     * {@code v}/chunking shape match {@code PublishExistingCredential.buildTransaction} exactly;
     * {@code m} generalizes the reference's hardcoded {@code l:[1447]}/{@code LEI} pair to caller
     * -supplied labels and extra entries.
     *
     * <p>{@code optionalM} may be {@code null} or empty. Its values are written as-is if already a
     * {@link String}, otherwise converted with {@link String#valueOf(Object)}.
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
        v.put("k", "KERI10");
        v.put("a", "ACDC10");
        map.put("v", v);

        map.put("m", authorizationMap(optionalM, authorizedLabels));

        return map;
    }

    /**
     * The Blake3-256 {@link Diger} qb64 digest of
     * {@code CborSerializationUtil.serialize(map1447.getMap())} — the same idiom
     * {@code AttestTransaction.buildTransaction} uses to seal a reeve metadata map into an
     * interaction event before anchoring it. Always starts with {@code "E"}, the CESR code for
     * Blake3-256.
     */
    public String digestOf(MetadataMap map1447) {
        try {
            byte[] cbor = CborSerializationUtil.serialize(map1447.getMap());
            return new Diger(new RawArgs(), cbor).getQb64();
        } catch (CborException e) {
            throw new IllegalStateException("Failed to CBOR-serialize a metadata map for digesting.", e);
        } catch (DigestException e) {
            throw new IllegalStateException("Failed to compute the Blake3-256 digest of a metadata map.", e);
        }
    }

    // --- internals ---

    private static MetadataMap authorizationMap(Map<String, Object> optionalM, List<Long> authorizedLabels) {
        MetadataMap m = MetadataBuilder.createMap();
        MetadataList l = MetadataBuilder.createList();
        for (Long label : authorizedLabels) {
            l.add(BigInteger.valueOf(label));
        }
        m.put("l", l);
        if (optionalM != null) {
            for (Map.Entry<String, Object> entry : optionalM.entrySet()) {
                Object value = entry.getValue();
                m.put(entry.getKey(), value instanceof String stringValue ? stringValue : String.valueOf(value));
            }
        }
        return m;
    }

    /** {@code Arrays.copyOfRange} loop identical to {@code PublishExistingCredential.splitIntoChunks}
     *  — ceiling division into {@link #CHAIN_CHUNK_SIZE}-byte chunks, last one shorter; an empty
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
