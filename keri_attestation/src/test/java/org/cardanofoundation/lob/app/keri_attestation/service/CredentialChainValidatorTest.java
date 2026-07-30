package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchemaRegistry;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.signify.cesr.Saider;
import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;

/**
 * {@code fixtures/vlei-chain-valid.cesr} is a real, genuine 3-level vLEI credential chain (see
 * {@link CesrChainReducerTest}'s javadoc for the full chain shape) and drives the CHAINED model. The
 * negative-case fixtures built inline here (cycle, missing parent, missing/revoked TEL, null issuer)
 * are hand-synthesized minimal ACDC/iss/rev maps — documented per-test — since no real captured
 * material exercises those failure paths.
 *
 * <p>The structural cases are a REGRESSION SUITE and must keep passing unchanged: the trust work layered
 * on top of this validator was only ever allowed to make it stricter, never to trade a structural check
 * away for a policy one.
 */
@SuppressWarnings("unchecked")
class CredentialChainValidatorTest {

    // --- real chain fixture (root -> QVI -> LE -> leaf) ---

    private static final String ROOT_AID = "EHt6RIKM4CHeMom5_yASwrKkFiqQquLH_S4aE1172GEe";
    private static final String LEAF_ISSUEE_AID = "EGDonzZJbqF3HqaEI_FOT1kL7x7P5xUmZQ76unf9suwR";
    private static final String LEAF_SCHEMA_SAID = "EG9587oc7lSUJGS7mtTkpmRUnJ8F5Ji79-e_pY4jt3Ik";
    private static final String LEAF_CREDENTIAL_SAID = "ELizup8Q4keLtgGDBcvBi3Y3c_EJcKiXwV2HzaJyZcdb";

    // --- synthetic chain constants ---

    private static final String SYN_SCHEMA = "Eschema000000000000000000000000000000";
    private static final String SYN_HOLDER = "Eholder0000000000000000000000000000";
    private static final String SYN_ISSUER = "Eissuer00000000000000000000000000000";
    private static final String SYN_REGISTRY = "Eregistry00000000000000000000000000000";

    /** The synthetic leaf's REAL SAID — derived, not invented, because the validator re-derives it. */
    private static final String SYN_LEAF = said(acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null));

    /** Wraps a reader as the ObjectProvider the validator takes, so tests construct it directly. */
    static ObjectProvider<CredentialTelStateReader> provider(CredentialTelStateReader reader) {
        return new ObjectProvider<>() {
            @Override
            public CredentialTelStateReader getObject() {
                return reader;
            }

            @Override
            public CredentialTelStateReader getIfAvailable() {
                return reader;
            }
        };
    }

    /** Stands in for the issuer's registry saying "issued". Revocation and unknown-state behaviour get
     *  their own tests; every other test here is about something else and should not have to care. */
    private static final CredentialTelStateReader ISSUED_BY_REGISTRY =
            (registryId, credentialSaid) -> CredentialTelStateReader.TelStatus.ISSUED;

    /** A validator whose registry trusts the real fixture's chain root under the CHAINED model. */
    private final CredentialChainValidator validator = chainedValidator(LEAF_SCHEMA_SAID, ROOT_AID);

    private static CredentialChainValidator chainedValidator(String schemaSaid, String... roots) {
        return new CredentialChainValidator(new CredentialSchemaRegistry(List.of(
                new CredentialSchema(schemaSaid, "vLEI Legal Entity", TrustModel.CHAINED, List.of(roots),
                        List.of(), List.of()))), provider(ISSUED_BY_REGISTRY));
    }

    private static CredentialChainValidator standaloneValidator(String schemaSaid, String... issuers) {
        return new CredentialChainValidator(new CredentialSchemaRegistry(List.of(
                new CredentialSchema(schemaSaid, "Foundation Employee", TrustModel.STANDALONE, List.of(),
                        List.of(issuers), List.of()))), provider(ISSUED_BY_REGISTRY));
    }

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
    void validChainReturnsLeafSaidSchemaAndTrustAnchor() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                LEAF_CREDENTIAL_SAID, LEAF_SCHEMA_SAID);

        assertTrue(result.isRight(), () -> result.isLeft() ? result.getLeft().getDetail() : "");
        assertEquals(LEAF_CREDENTIAL_SAID, result.get().credentialSaid());
        assertEquals(LEAF_SCHEMA_SAID, result.get().schemaSaid());
        assertEquals("vLEI Legal Entity", result.get().schemaName());
        assertEquals(TrustModel.CHAINED, result.get().trustModel());
        // The chain root, not the QVI that issued the leaf — reporting one as the other would misstate
        // both who issued the credential and why it was believed.
        assertEquals(ROOT_AID, result.get().trustAnchorAid());
    }

    @Test
    void aCallerWithNoSchemaClaimStillGetsTheSchemaGated() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                LEAF_CREDENTIAL_SAID, null);

        assertTrue(result.isRight(), () -> result.isLeft() ? result.getLeft().getDetail() : "");
        assertEquals(LEAF_SCHEMA_SAID, result.get().schemaSaid());
    }

    // --- the schema gate ---

    @Test
    void aSchemaTheRegistryDoesNotKnowIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");
        CredentialChainValidator other = chainedValidator("EDIFFERENT_SCHEMA_ENTIRELY0000000000", ROOT_AID);

        Either<ProblemDetail, ValidatedCredential> result = other.validate(fullCesr, LEAF_ISSUEE_AID,
                LEAF_CREDENTIAL_SAID, null);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("does not accept"));
    }

    /**
     * Schema confusion: a real credential of an accepted schema, presented as if it were a different
     * accepted schema. Accepting it would let a credential earn the trust rules of a schema it does not
     * belong to.
     */
    @Test
    void aCredentialPresentedUnderTheWrongSchemaIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                LEAF_CREDENTIAL_SAID, "ESOME_OTHER_SCHEMA00000000000000000");

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("not the claimed"));
    }

    // --- the two trust models ---

    @Test
    void aChainTerminatingInAnUntrustedRootIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");
        CredentialChainValidator other = chainedValidator(LEAF_SCHEMA_SAID, "ENOT_A_TRUSTED_ROOT_AID00000000000000");

        Either<ProblemDetail, ValidatedCredential> result = other.validate(fullCesr, LEAF_ISSUEE_AID,
                LEAF_CREDENTIAL_SAID, LEAF_SCHEMA_SAID);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("not a trusted root"));
    }

    @Test
    void aStandaloneCredentialFromATrustedIssuerIsAccepted() {
        String cesr = standaloneChain();

        Either<ProblemDetail, ValidatedCredential> result =
                standaloneValidator(SYN_SCHEMA, SYN_ISSUER).validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);

        assertTrue(result.isRight(), () -> result.isLeft() ? result.getLeft().getDetail() : "");
        assertEquals(TrustModel.STANDALONE, result.get().trustModel());
        // For STANDALONE the issuer IS the anchor — there is no chain above it to trace.
        assertEquals(SYN_ISSUER, result.get().leafIssuerAid());
        assertEquals(SYN_ISSUER, result.get().trustAnchorAid());
    }

    @Test
    void aStandaloneCredentialFromAnUntrustedIssuerIsRejected() {
        String cesr = standaloneChain();

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, "EsomeoneElse000000")
                .validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("not a trusted issuer"));
    }

    /**
     * The two models must not be interchangeable: a standalone credential whose issuer is trusted under
     * STANDALONE must NOT pass when the same AID is configured as a CHAINED root, and vice versa. The
     * lists are consulted for different things.
     */
    @Test
    void trustAnchorsAreNotSharedBetweenTheTwoModels() {
        String cesr = standaloneChain();

        // Configured CHAINED with the issuer as a root: this synthetic leaf has no edges, so it IS its
        // own root and the root issuer matches — accepted, but as a CHAINED decision.
        Either<ProblemDetail, ValidatedCredential> asChained =
                chainedValidator(SYN_SCHEMA, SYN_ISSUER).validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);
        assertTrue(asChained.isRight(), () -> asChained.isLeft() ? asChained.getLeft().getDetail() : "");
        assertEquals(TrustModel.CHAINED, asChained.get().trustModel());

        // The same AID listed as a STANDALONE trusted-issuer while the schema is CHAINED does nothing:
        // trustedIssuers is not consulted for a CHAINED schema.
        CredentialChainValidator misconfigured = new CredentialChainValidator(new CredentialSchemaRegistry(
                List.of(new CredentialSchema(SYN_SCHEMA, "x", TrustModel.CHAINED,
                        List.of("EsomeOtherRoot0000"), List.of(SYN_ISSUER), List.of()))), provider(ISSUED_BY_REGISTRY));
        assertTrue(misconfigured.validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA).isLeft());
    }

    // --- unique leaf identification (§6.2) ---

    @Test
    void aCredentialSaidNotInTheChainIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                "ENOT_IN_THIS_CHAIN000000000000000000", LEAF_SCHEMA_SAID);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("not present in the chain"));
    }

    @Test
    void issueeMismatchIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr,
                "ESOMEONE_ELSE_ENTIRELY000000000000000", LEAF_CREDENTIAL_SAID, LEAF_SCHEMA_SAID);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("is issued to"));
    }

    /**
     * Without a credential SAID the validator would be free to search the stream for any credential
     * that happens to pass, which is precisely the ambiguity an attacker packs a multi-credential
     * stream to exploit.
     */
    @Test
    void aMissingCredentialSaidIsRejectedRatherThanSearched() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        Either<ProblemDetail, ValidatedCredential> result = validator.validate(fullCesr, LEAF_ISSUEE_AID,
                null, LEAF_SCHEMA_SAID);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("cannot be identified unambiguously"));
    }

    @Test
    void aMissingHolderAidIsRejected() throws IOException {
        String fullCesr = fixture("vlei-chain-valid.cesr");

        assertTrue(validator.validate(fullCesr, null, LEAF_CREDENTIAL_SAID, LEAF_SCHEMA_SAID).isLeft());
    }

    // --- authenticity: the presenter does not get to answer these ---

    private static CredentialChainValidator standaloneValidatorWithTel(CredentialTelStateReader tel) {
        return new CredentialChainValidator(new CredentialSchemaRegistry(List.of(
                new CredentialSchema(SYN_SCHEMA, "Foundation Employee", TrustModel.STANDALONE, List.of(),
                        List.of(SYN_ISSUER), List.of()))), provider(tel));
    }

    /**
     * The replay this closes: a stream captured BEFORE a revocation contains a valid {@code iss} and no
     * {@code rev}, so it satisfies every check that reads only the presented stream. Asking the issuer's
     * registry is the only way to see the revocation that happened afterwards.
     */
    @Test
    void aCredentialRevokedByItsIssuerIsRejectedEvenWhenThePresentedStreamLooksValid() {
        String cesr = standaloneChain();

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidatorWithTel(
                (registryId, credentialSaid) -> CredentialTelStateReader.TelStatus.REVOKED)
                .validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("revoked by its issuer"));
    }

    /** An unanswerable question about revocation is not a "no". */
    @Test
    void aCredentialWhoseRegistryStateCannotBeEstablishedIsRejected() {
        String cesr = standaloneChain();

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidatorWithTel(
                (registryId, credentialSaid) -> CredentialTelStateReader.TelStatus.UNKNOWN)
                .validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("not the same as valid"));
    }

    /** The registry is asked about the credential's OWN registry id, not some default. */
    @Test
    void theRegistryIsQueriedWithTheCredentialsOwnRegistryId() {
        String cesr = standaloneChain();
        List<String> asked = new ArrayList<>();

        standaloneValidatorWithTel((registryId, credentialSaid) -> {
            asked.add(registryId + "/" + credentialSaid);
            return CredentialTelStateReader.TelStatus.ISSUED;
        }).validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);

        assertEquals(List.of(SYN_REGISTRY + "/" + SYN_LEAF), asked);
    }

    /**
     * The forgery this closes: a self-addressed credential naming a TRUSTED issuer, issued through a
     * registry the attacker controls. Self-addressing proves the body matches its SAID and the registry
     * lookup proves the SAID was issued somewhere — neither proves the named issuer was involved.
     * Requiring the registry's controller to BE that issuer is what ties the two together.
     */
    @Test
    void aCredentialIssuedFromARegistryItsClaimedIssuerDoesNotControlIsRejected() {
        Map<String, Object> leaf = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null);
        // The registry exists and says "issued", but somebody else incepted it.
        String cesr = CESRStreamUtil.makeCESRStream(
                List.of(vcpEvent("EattackerControlsThisRegistry000"), issEvent(said(leaf)), leaf),
                List.of("", "", ""));

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, SYN_ISSUER)
                .validate(cesr, SYN_HOLDER, said(leaf), SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("which is controlled by"));
    }

    /** A chain that omits its registry inception cannot attribute the issuance to anyone. */
    @Test
    void aCredentialWhoseRegistryInceptionIsAbsentIsRejected() {
        Map<String, Object> leaf = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null);
        String cesr = CESRStreamUtil.makeCESRStream(List.of(issEvent(said(leaf)), leaf), List.of("", ""));

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, SYN_ISSUER)
                .validate(cesr, SYN_HOLDER, said(leaf), SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("cannot be tied to an issuer"));
    }

    /** Two ACDCs claiming one SAID: at most one is genuine, and picking one is not our choice to make. */
    @Test
    void aChainCarryingTwoDifferentCredentialsUnderOneSaidIsRejected() {
        Map<String, Object> genuine = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null);
        Map<String, Object> impostor = new LinkedHashMap<>(genuine);
        impostor.put("i", "EsomeoneElse000000000000000000000");
        String cesr = CESRStreamUtil.makeCESRStream(
                List.of(vcpEvent(SYN_ISSUER), issEvent(said(genuine)), genuine, impostor),
                List.of("", "", "", ""));

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, SYN_ISSUER)
                .validate(cesr, SYN_HOLDER, said(genuine), SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("more than once"));
    }

    /**
     * Content is bound to the SAID: edit any field of a genuinely-issued credential and it no longer
     * derives to the SAID its issuer's registry vouches for. Without this, an attacker could take a real
     * SAID and present completely different attributes under it.
     */
    @Test
    void aCredentialWhoseBodyWasEditedAfterIssuanceIsRejected() {
        Map<String, Object> leaf = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null);
        String genuineSaid = said(leaf);
        // Same SAID, different issuee — exactly the swap the SAID exists to prevent.
        Map<String, Object> tampered = new LinkedHashMap<>(leaf);
        Map<String, Object> attributes = new LinkedHashMap<>((Map<String, Object>) leaf.get("a"));
        attributes.put("i", "EsomebodyElse000000000000000000000");
        tampered.put("a", attributes);
        String cesr = CESRStreamUtil.makeCESRStream(
                List.of(vcpEvent(SYN_ISSUER), issEvent(genuineSaid), tampered), List.of("", "", ""));

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, SYN_ISSUER)
                .validate(cesr, "EsomebodyElse000000000000000000000", genuineSaid, SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("does not hash to its own SAID"));
    }

    // --- structural regression suite: these must not weaken ---

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
                LEAF_CREDENTIAL_SAID, LEAF_SCHEMA_SAID);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("revoked"));
    }

    @Test
    void missingIssEventIsRejected() {
        // Hand-built minimal single-credential chain with no matching iss event at all.
        Map<String, Object> leaf = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null);
        String cesr = CESRStreamUtil.makeCESRStream(List.of(vcpEvent(SYN_ISSUER), leaf), List.of("", ""));

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, SYN_ISSUER)
                .validate(cesr, SYN_HOLDER, said(leaf), SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("no issuance"));
    }

    @Test
    void edgeReferencingAMissingParentCredentialIsRejected() {
        Map<String, Object> leaf = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA,
                Map.of("parent", Map.of("n", "Emissing_parent_said0000000000000000", "s", "Esomeschema00000000000000000000000")));
        String cesr = CESRStreamUtil.makeCESRStream(
                List.of(vcpEvent(SYN_ISSUER), issEvent(said(leaf)), leaf), List.of("", "", ""));

        Either<ProblemDetail, ValidatedCredential> result = standaloneValidator(SYN_SCHEMA, SYN_ISSUER)
                .validate(cesr, SYN_HOLDER, said(leaf), SYN_SCHEMA);

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
        String issJson = "{\"v\":\"KERI10JSON0000ed_\",\"t\":\"iss\",\"d\":\"Eissleaf000000000000000000000000000\","
                + "\"i\":\"" + SYN_LEAF + "\",\"s\":\"0\",\"ri\":\"Eregistry00000000000000000000000000000\","
                + "\"dt\":\"2026-07-21T00:00:00.000000+00:00\"}";
        String acdcJson = "{\"v\":\"ACDC10JSON000197_\",\"d\":\"" + SYN_LEAF + "\",\"i\":null,"
                + "\"ri\":\"Eregistry00000000000000000000000000000\",\"s\":\"" + SYN_SCHEMA + "\","
                + "\"a\":{\"d\":\"Eattr00000000000000000000000000000000\",\"i\":\"" + SYN_HOLDER + "\"}}";
        String cesr = issJson + acdcJson;

        Either<ProblemDetail, ValidatedCredential> result =
                standaloneValidator(SYN_SCHEMA, SYN_ISSUER).validate(cesr, SYN_HOLDER, SYN_LEAF, SYN_SCHEMA);

        // Rejected for its SAID rather than its null issuer: an invented SAID is caught first now. The
        // property this pins is unchanged — a hostile chain resolves to a clean rejection, never an NPE
        // escaping the walk. The null-issuer guard behind it stays, for the same reason as the cycle
        // guard above.
        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
    }

    /**
     * A cycle can no longer be BUILT, which is a stronger outcome than detecting one: A's SAID is the
     * digest of contents that include the edge naming B, and B's the digest of contents naming A, so
     * neither can be self-addressing. This drives that — the chain is rejected before the walk starts.
     *
     * <p>The cycle guard in {@code validateAncestry} is deliberately kept. It costs a set lookup, it is
     * what stops the walk recursing forever if self-addressing is ever relaxed or bypassed, and a
     * termination guarantee that depends on a check made somewhere else is not a guarantee.
     */
    @Test
    void mutuallyReferencingCredentialsCannotBeSelfAddressingAndAreRejected() {
        String saidA = "EcredA0000000000000000000000000000000000000A";
        String saidB = "EcredB0000000000000000000000000000000000000B";
        String issuerA = "EissuerA000000000000000000000000000000";
        String issuerB = "EissuerB000000000000000000000000000000";

        Map<String, Object> credA = rawAcdc(saidA, issuerA, issuerB, Map.of("n", saidB));
        Map<String, Object> credB = rawAcdc(saidB, issuerB, issuerA, Map.of("n", saidA));
        String cesr = CESRStreamUtil.makeCESRStream(List.of(issEvent(saidA), issEvent(saidB), credA, credB),
                List.of("", "", "", ""));

        Either<ProblemDetail, ValidatedCredential> result =
                chainedValidator(SYN_SCHEMA, "Esome_root_that_is_never_reached00000")
                        .validate(cesr, issuerB, saidA, SYN_SCHEMA);

        assertTrue(result.isLeft());
        assertEquals(KeriAttestationProblems.CREDENTIAL_REJECTED, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("does not hash to its own SAID"),
                () -> "expected a self-addressing rejection, got: " + result.getLeft().getDetail());
    }

    // --- hand-built minimal ACDC/iss fixtures ---

    /** A single self-rooted credential issued by {@link #SYN_ISSUER} to {@link #SYN_HOLDER}. */
    private static String standaloneChain() {
        Map<String, Object> leaf = acdc(SYN_ISSUER, SYN_HOLDER, SYN_SCHEMA, null);
        return CESRStreamUtil.makeCESRStream(
                List.of(vcpEvent(SYN_ISSUER), issEvent(said(leaf)), leaf), List.of("", "", ""));
    }

    /**
     * The registry inception naming {@code controller} as its controller. A chain without one is
     * rejected: a credential issued from a registry nobody can be tied to is not attributable.
     */
    private static Map<String, Object> vcpEvent(String controller) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", "KERI10JSON0000ed_");
        event.put("t", "vcp");
        event.put("d", SYN_REGISTRY);
        event.put("i", SYN_REGISTRY);
        event.put("ii", controller);
        event.put("s", "0");
        return event;
    }

    /** A deliberately NOT-saidified ACDC: its {@code d} is invented, which is the point. */
    private static Map<String, Object> rawAcdc(String said, String issuer, String issueeAid,
            Map<String, Object> edge) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("d", "Eattr00000000000000000000000000000000");
        a.put("i", issueeAid);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", "ACDC10JSON000197_");
        event.put("d", said);
        event.put("i", issuer);
        event.put("ri", SYN_REGISTRY);
        event.put("s", SYN_SCHEMA);
        event.put("a", a);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("edge", edge);
        e.put("d", "");
        event.put("e", e);
        return event;
    }

    private static String said(Map<String, Object> acdc) {
        return (String) acdc.get("d");
    }

    /**
     * Builds a synthetic ACDC and SAIDIFIES it, so its {@code d} is genuinely the digest of its own
     * contents.
     *
     * <p>An invented SAID would now be rejected before any structural check ran, which would make every
     * negative test below pass for the wrong reason — they would all be proving the self-addressing
     * check works rather than the thing each one is named after.
     */
    private static Map<String, Object> acdc(String issuer, String issueeAid, String schema,
            Map<String, Object> edges) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("d", "Eattr00000000000000000000000000000000");
        a.put("i", issueeAid);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", "ACDC10JSON000197_");
        event.put("d", "");
        event.put("i", issuer);
        event.put("ri", SYN_REGISTRY);
        event.put("s", schema);
        event.put("a", a);
        if (edges != null) {
            Map<String, Object> e = new LinkedHashMap<>(edges);
            e.put("d", "");
            event.put("e", e);
        }
        try {
            return Saider.saidify(event).sad();
        } catch (DigestException ex) {
            throw new IllegalStateException("could not saidify the synthetic ACDC", ex);
        }
    }

    private static Map<String, Object> issEvent(String credentialSaid) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("v", "KERI10JSON0000ed_");
        event.put("t", "iss");
        event.put("d", "Eiss" + credentialSaid.substring(4));
        event.put("i", credentialSaid);
        event.put("s", "0");
        event.put("ri", SYN_REGISTRY);
        event.put("dt", "2026-07-21T00:00:00.000000+00:00");
        return event;
    }
}
