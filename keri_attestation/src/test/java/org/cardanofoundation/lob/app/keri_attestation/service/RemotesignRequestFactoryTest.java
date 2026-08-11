package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.signify.cesr.Saider;

/**
 * Golden-vector coverage for {@link RemotesignRequestFactory}: this is the exact
 * payload shape a real Veridian wallet is now known to require — a bare, unsaidified {@code {"d":
 * digest}} ("variant A") never produced a wallet notification in live testing. Every assertion here is
 * a direct, independently-reproduced check against {@link Saider#saidify}, not a mock of it — a
 * regression here (wrong field set, wrong insertion order, wrong dummy value, or skipping saidify
 * entirely) is exactly the class of defect this pins against.
 */
class RemotesignRequestFactoryTest {

    private static final String WALLET_AID = "EWALLETAID00000000000000000000000000000";
    private static final String METADATA_LABEL = "1447";
    private static final String METADATA_DIGEST = "Emetadatadigest0000000000000000000000000000";

    private final RemotesignRequestFactory factory = new RemotesignRequestFactory();

    @Test
    void anchorRequestKedCarriesExactlyTheFourExpectedFieldsInInsertionOrder() {
        Map<String, Object> ked = factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST);

        assertEquals(List.of("i", "d", "metadataLabel", "metadataDigest"), new ArrayList<>(ked.keySet()));
        assertEquals(WALLET_AID, ked.get("i"));
        assertEquals(METADATA_LABEL, ked.get("metadataLabel"));
        assertEquals(METADATA_DIGEST, ked.get("metadataDigest"));
    }

    @Test
    void anchorRequestKedsDFieldIsASaidNotTheRawMetadataDigestPassedThrough() {
        Map<String, Object> ked = factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST);

        Object said = ked.get("d");
        assertTrue(said instanceof String, "d must be a String SAID");
        String saidStr = (String) said;
        assertTrue(saidStr.startsWith("E"), "Blake3-256 CESR qb64 SAIDs start with 'E', was: " + saidStr);
        // The wallet anchors the SAID of the WHOLE payload
        // (i, d, metadataLabel, metadataDigest), not the caller-chosen metadataDigest passed straight
        // through unsaidified (that was the "variant A" shape a real wallet silently dropped).
        assertNotEquals(METADATA_DIGEST, saidStr);
    }

    @Test
    void anchorRequestKedIsDeterministicForTheSameInputs() {
        Map<String, Object> first = factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST);
        Map<String, Object> second = factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST);

        assertEquals(first, second);
        assertEquals(first.get("d"), second.get("d"));
    }

    @Test
    void anchorRequestKedsSaidChangesWhenTheWalletAidChanges() {
        String saidA = (String) factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST).get("d");
        String saidB = (String) factory
                .anchorRequestKed("EOTHERWALLETAID0000000000000000000000000", METADATA_LABEL, METADATA_DIGEST)
                .get("d");

        assertNotEquals(saidA, saidB);
    }

    @Test
    void anchorRequestKedsSaidChangesWhenTheMetadataLabelChanges() {
        String saidA = (String) factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST).get("d");
        String saidB = (String) factory.anchorRequestKed(WALLET_AID, "170", METADATA_DIGEST).get("d");

        assertNotEquals(saidA, saidB);
    }

    @Test
    void anchorRequestKedsSaidChangesWhenTheMetadataDigestChanges() {
        String saidA = (String) factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST).get("d");
        String saidB = (String) factory
                .anchorRequestKed(WALLET_AID, METADATA_LABEL, "Eanotherdigest000000000000000000000000000")
                .get("d");

        assertNotEquals(saidA, saidB);
    }

    /**
     * Independently reproduces the factory's SAID by calling {@link Saider#saidify} directly against a
     * hand-built payload of the exact expected shape ({@code i} present before
     * saidifying). If this ever disagrees with the factory's own output, the factory has silently
     * drifted from the documented contract — this is the "golden vector" the class javadoc/design
     * doc's payload shape claims are checked against.
     */
    @Test
    void anchorRequestKedMatchesAnIndependentlyComputedSaidOverTheSamePayloadShape() throws Exception {
        Map<String, Object> expectedPayload = new LinkedHashMap<>();
        expectedPayload.put("i", WALLET_AID);
        expectedPayload.put("d", "");
        expectedPayload.put("metadataLabel", METADATA_LABEL);
        expectedPayload.put("metadataDigest", METADATA_DIGEST);
        Map<String, Object> expected = Saider.saidify(expectedPayload).sad();

        Map<String, Object> actual = factory.anchorRequestKed(WALLET_AID, METADATA_LABEL, METADATA_DIGEST);

        assertEquals(expected, actual);
    }
}
