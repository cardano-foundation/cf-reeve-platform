package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchemaRegistry;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;

/**
 * {@code fixtures/vlei-chain-valid.cesr} is a <strong>real</strong> full CESR credential stream —
 * genuine SAIDs/signatures from an actual signify-java vLEI issuance run: root AID
 * {@code EHt6RIKM...} issues a QVI credential to {@code EFTzvyVmz...}, which issues an LE-schema
 * credential to {@code EBfMzafqg...} (edge {@code qvi}), which issues a third credential to
 * {@code EGDonzZJb...} (edge {@code le}) — a genuine three-level chain, not synthesized.
 */
class CesrChainReducerTest {

    private final CesrChainReducer reducer = new CesrChainReducer();

    private static String fixture(String name) throws IOException {
        try (InputStream in = CesrChainReducerTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IOException("Fixture not found on classpath: fixtures/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static String eventType(Map<String, Object> parsedEntry) {
        Map<String, Object> event = (Map<String, Object>) parsedEntry.get("event");
        Object t = event.get("t");
        return t != null ? t.toString() : "ACDC";
    }

    @Test
    void reducesToVcpThenIssThenAcdcAndRoundTripsThroughParseCESRDataWithoutLoss() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        List<Map<String, Object>> original = CESRStreamUtil.parseCESRData(fullCesr);
        List<Map<String, Object>> originalVcp = original.stream().filter(e -> "vcp".equals(eventType(e))).toList();
        List<Map<String, Object>> originalIss = original.stream().filter(e -> "iss".equals(eventType(e))).toList();
        List<Map<String, Object>> originalAcdc = original.stream().filter(e -> "ACDC".equals(eventType(e))).toList();
        // Sanity on the fixture itself: a genuine 3-level chain has exactly 3 of each.
        assertEquals(3, originalVcp.size());
        assertEquals(3, originalIss.size());
        assertEquals(3, originalAcdc.size());

        byte[] reduced = reducer.reduceToVcpIssAcdc(fullCesr);
        String reducedStream = new String(reduced, StandardCharsets.UTF_8);

        // Round-trips: re-parses via CESRStreamUtil without loss.
        List<Map<String, Object>> reparsed = CESRStreamUtil.parseCESRData(reducedStream);
        assertEquals(9, reparsed.size(), "expected exactly 3 vcp + 3 iss + 3 ACDC events");

        // Exactly vcp+iss+ACDC events, in that grouped canonical order.
        List<String> types = reparsed.stream().map(CesrChainReducerTest::eventType).toList();
        assertEquals(List.of("vcp", "vcp", "vcp", "iss", "iss", "iss", "ACDC", "ACDC", "ACDC"), types);

        // No loss: same vcp/iss events (content-identical, attachments preserved) as the original parse.
        for (int i = 0; i < 3; i++) {
            assertEquals(originalVcp.get(i).get("event"), reparsed.get(i).get("event"));
            assertEquals(originalVcp.get(i).get("atc"), reparsed.get(i).get("atc"));
            assertFalse(((String) reparsed.get(i).get("atc")).isEmpty(), "vcp attachment must be preserved");

            assertEquals(originalIss.get(i).get("event"), reparsed.get(3 + i).get("event"));
            assertEquals(originalIss.get(i).get("atc"), reparsed.get(3 + i).get("atc"));
            assertFalse(((String) reparsed.get(3 + i).get("atc")).isEmpty(), "iss attachment must be preserved");

            // ACDCs carry no separate attachment (their signature lives in the iss event's atc).
            assertEquals(originalAcdc.get(i).get("event"), reparsed.get(6 + i).get("event"));
            assertEquals("", reparsed.get(6 + i).get("atc"));
        }
    }

    @Test
    void reducedStreamStillValidatesAsAGenuineChain() throws IOException {
        // Design acceptance criterion: the reduced stream must round-trip through the
        // validator, not just through the raw parser — a reduction that silently drops a field the
        // validator needs (e.g. an ACDC's "e" edge map) would pass the parse-only check above but
        // must still fail here.
        String fullCesr = fixture("vlei-chain-valid.cesr");
        byte[] reduced = reducer.reduceToVcpIssAcdc(fullCesr);
        String reducedStream = new String(reduced, StandardCharsets.UTF_8);

        CredentialChainValidator validator = new CredentialChainValidator(new CredentialSchemaRegistry(
                List.of(new CredentialSchema("EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik", "vLEI Legal Entity",
                        TrustModel.CHAINED, List.of("EHt6RIKM4CHeMom5_yASwrKkFiqQquLH_S4aE1172GEe"),
                        List.of(), List.of()))),
                CredentialChainValidatorTest.provider(
                        (registryId, credentialSaid) -> CredentialTelStateReader.TelStatus.ISSUED));
        var result = validator.validate(reducedStream, "EGDonzZJbqF3HqaEI_FOT1kL7x7P5xUmZQ76unf9suwR",
                "ELizup8Q4keLtgGDBcvBi3Y3c_EJcKiXwV2HzaJyZcdb",
                "EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik");

        assertTrue(result.isRight(), () -> "expected the reduced stream to still validate: "
                + (result.isLeft() ? result.getLeft().getDetail() : ""));
        assertEquals("ELizup8Q4keLtgGDBcvBi3Y3c_EJcKiXwV2HzaJyZcdb", result.get().credentialSaid());
        assertEquals("EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik", result.get().schemaSaid());
    }
}
