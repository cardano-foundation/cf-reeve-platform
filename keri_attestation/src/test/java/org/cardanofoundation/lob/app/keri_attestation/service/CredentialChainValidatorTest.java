package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;

/**
 * {@code fixtures/vlei-chain-valid.cesr} is a real, genuine 3-level vLEI credential chain (see
 * {@link CesrChainReducerTest}'s javadoc for the full chain shape). The negative-case fixtures built
 * inline in this class (cycle, missing parent, missing/revoked TEL) are hand-synthesized minimal
 * ACDC/iss/rev maps — documented per-test — since no real captured material exercises those failure
 * paths.
 */
class CredentialChainValidatorTest {

    // --- real chain fixture (root -> QVI -> LE -> leaf) ---

    private static final String ROOT_AID = "EHt6RIKM4CHeMom5_yASwrKkFiqQquLH_S4aE1172GEe";
    private static final String LEAF_ISSUEE_AID = "EGDonzZJbqF3HqaEI_FOT1kL7x7P5xUmZQ76unf9suwR";
    private static final String LEAF_SCHEMA_SAID = "EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik";
    private static final String LEAF_CREDENTIAL_SAID = "ELizup8Q4keLtgGDBcvBi3Y3c_EJcKiXwV2HzaJyZcdb";

    private final CredentialChainValidator validator = new CredentialChainValidator();

    private static String fixture(String name) throws IOException {
        try (InputStream in = CredentialChainValidatorTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IOException("Fixture not found on classpath: fixtures/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- happy path ---

    @Test
    void validChainReturnsLeafSaidAndSchema() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                List.of(LEAF_SCHEMA_SAID), List.of(ROOT_AID));

        assertTrue(result.isRight(), () -> result.isLeft() ? result.getLeft().getDetail() : "");
        assertEquals(LEAF_CREDENTIAL_SAID, result.get().credentialSaid());
        assertEquals(LEAF_SCHEMA_SAID, result.get().schemaSaid());
    }

    // --- brief's required negative cases (real chain, mismatched policy) ---

    @Test
    void issueeMismatchIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr,
                "ESOMEONE_ELSE_ENTIRELY000000000000000", List.of(LEAF_SCHEMA_SAID), List.of(ROOT_AID));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("No credential"));
    }

    @Test
    void schemaNotAllowlistedIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                List.of("EDIFFERENT_SCHEMA_NOT_ALLOWED0000000"), List.of(ROOT_AID));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("not in the allowed schema list"));
    }

    @Test
    void chainNotTerminatingInATrustedRootIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                List.of(LEAF_SCHEMA_SAID), List.of("ENOT_A_TRUSTED_ROOT_AID00000000000000"));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("not a trusted root AID"));
    }

    @Test
    void revokedLeafTelIsRejected() throws IOException {
        // Synthetic augmentation of the real fixture: append a hand-built "rev" event for the leaf
        // credential's SAID. The validator only inspects iss/rev "i" (credential SAID) and "t"
        // (event type) fields, so this minimal rev event is sufficient without a structurally-valid
        // signature attachment — deep TEL/KEL signature verification is documented as KERIA's job,
        // not this validator's (see its javadoc).
        String fullCesr = fixture("vlei-chain-valid.cesr");
        Map<String, Object> revEvent = new LinkedHashMap<>();
        revEvent.put("v", "KERI10JSON0000ed_");
        revEvent.put("t", "rev");
        revEvent.put("d", "ESYNTHETIC_REV_EVENT_SAID000000000000");
        revEvent.put("i", LEAF_CREDENTIAL_SAID);
        revEvent.put("s", "1");
        revEvent.put("ri", "EHC7MXoD-M-yNuTnt1UhHxuMmadzHIy1i5QpvMaFFXCK");
        revEvent.put("p", "EElwMMNDjn2JeU2ub2shBsuV9yrC0vgcBxs0QGXZkQTE");
        revEvent.put("dt", "2026-07-21T00:00:00.000000+00:00");
        String augmented = fullCesr + CESRStreamUtil.makeCESRStream(List.of(revEvent), List.of(""));

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(augmented, LEAF_ISSUEE_AID,
                List.of(LEAF_SCHEMA_SAID), List.of(ROOT_AID));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("revoked"));
    }

    // --- bonus: edge-walk termination and structural-integrity cases (self-review) ---

    @Test
    void missingIssEventIsRejected() {
        // Hand-built minimal single-credential chain with no matching iss event at all.
        Map<String, Object> leaf = acdc("Eleaf000000000000000000000000000000", "Eroot00000000000000000000000000000000",
                "Eholder0000000000000000000000000000", "Eschema000000000000000000000000000000", null);
        String cesr = CESRStreamUtil.makeCESRStream(List.of(leaf), List.of(""));

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(cesr, "Eholder0000000000000000000000000000",
                List.of("Eschema000000000000000000000000000000"), List.of("Eroot00000000000000000000000000000000"));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("no issuance"));
    }

    @Test
    void edgeReferencingAMissingParentCredentialIsRejected() {
        Map<String, Object> leaf = acdc("Eleaf000000000000000000000000000000", "Eissuer00000000000000000000000000000",
                "Eholder0000000000000000000000000000", "Eschema000000000000000000000000000000",
                Map.of("parent", Map.of("n", "Emissing_parent_said0000000000000000", "s", "Esomeschema00000000000000000000000")));
        Map<String, Object> iss = issEvent("Eleaf000000000000000000000000000000");

        String cesr = CESRStreamUtil.makeCESRStream(List.of(iss, leaf), List.of("", ""));

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(cesr, "Eholder0000000000000000000000000000",
                List.of("Eschema000000000000000000000000000000"), List.of("Eissuer00000000000000000000000000000"));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("unknown parent credential"));
    }

    @Test
    void acdcWithNullIssuerIsRejectedNotThrown() {
        // isAcdc() only requires the "i" key to be present (containsKey), not its value non-null — a
        // structurally-plausible but malformed/hostile ACDC could carry i:null. Must resolve to a clean
        // CREDENTIAL_REJECTED, never an NPE out of the walk. Built as raw JSON text rather than via the
        // acdc()/CESRStreamUtil.makeCESRStream helpers: those round-trip the event map through
        // Utils.jsonStringify, which drops null-valued entries entirely (so the "i" key wouldn't even
        // survive re-parsing) — writing the wire text directly guarantees "i":null is actually present,
        // exactly as a real malformed/hostile stream could present it.
        String credentialSaid = "Eleaf000000000000000000000000000000";
        String issJson = "{\"v\":\"KERI10JSON0000ed_\",\"t\":\"iss\",\"d\":\"Eissleaf000000000000000000000000000\","
                + "\"i\":\"" + credentialSaid + "\",\"s\":\"0\",\"ri\":\"Eregistry00000000000000000000000000000\","
                + "\"dt\":\"2026-07-21T00:00:00.000000+00:00\"}";
        String acdcJson = "{\"v\":\"ACDC10JSON000197_\",\"d\":\"" + credentialSaid + "\",\"i\":null,"
                + "\"ri\":\"Eregistry00000000000000000000000000000\",\"s\":\"Eschema000000000000000000000000000000\","
                + "\"a\":{\"d\":\"Eattr00000000000000000000000000000000\",\"i\":\"Eholder0000000000000000000000000000\"}}";
        String cesr = issJson + acdcJson;

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(cesr,
                "Eholder0000000000000000000000000000", List.of("Eschema000000000000000000000000000000"),
                List.of("Eroot00000000000000000000000000000000"));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("no issuer"), () -> "expected a no-issuer rejection, got: "
                + result.getLeft().getDetail());
    }

    @Test
    void edgeWalkTerminatesOnACycleInsteadOfLoopingForever() {
        // Two credentials whose edges reference each other: A's edge points at B, B's edge points at
        // A. Neither ever reaches a childless (root) node, so without cycle detection the ancestry
        // walk would recurse/loop forever. issuer/issuee are deliberately matched at each hop so the
        // walk gets past the issuer-chain check and actually reaches the cycle-detection guard.
        String saidA = "EcredA00000000000000000000000000000000";
        String saidB = "EcredB00000000000000000000000000000000";
        String issuerA = "EissuerA000000000000000000000000000000";
        String issuerB = "EissuerB000000000000000000000000000000";
        String schema = "Eschema000000000000000000000000000000";

        Map<String, Object> credA = acdc(saidA, issuerA, issuerB, schema,
                Map.of("edge", Map.of("n", saidB, "s", schema)));
        Map<String, Object> credB = acdc(saidB, issuerB, issuerA, schema,
                Map.of("edge", Map.of("n", saidA, "s", schema)));
        Map<String, Object> issA = issEvent(saidA);
        Map<String, Object> issB = issEvent(saidB);

        String cesr = CESRStreamUtil.makeCESRStream(List.of(issA, issB, credA, credB), List.of("", "", "", ""));

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(cesr, issuerB,
                List.of(schema), List.of("Esome_root_that_is_never_reached00000"));

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("cycle"), () -> "expected a cycle rejection, got: "
                + result.getLeft().getDetail());
    }

    // --- hand-built minimal ACDC/iss fixtures ---

    private static Map<String, Object> acdc(String said, String issuer, String issueeAid, String schema,
            Map<String, Object> edges) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("d", "Eattr00000000000000000000000000000000");
        a.put("i", issueeAid);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", "ACDC10JSON000197_");
        event.put("d", said);
        event.put("i", issuer);
        event.put("ri", "Eregistry00000000000000000000000000000");
        event.put("s", schema);
        event.put("a", a);
        if (edges != null) {
            Map<String, Object> e = new LinkedHashMap<>(edges);
            e.put("d", "");
            event.put("e", e);
        }
        return event;
    }

    private static Map<String, Object> issEvent(String credentialSaid) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", "KERI10JSON0000ed_");
        event.put("t", "iss");
        event.put("d", "Eiss" + credentialSaid.substring(4));
        event.put("i", credentialSaid);
        event.put("s", "0");
        event.put("ri", "Eregistry00000000000000000000000000000");
        event.put("dt", "2026-07-21T00:00:00.000000+00:00");
        return event;
    }
}
