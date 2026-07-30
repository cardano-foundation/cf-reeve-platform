package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;

/**
 * The card-import contract with the issuing indexer.
 *
 * <p>What is verified is a KEL seal, not a transaction: the issuing ceremony publishes nothing
 * on-chain, so these tests drive a mocked KERI agent and never a chain client. The value the wallet
 * seals is the SAID of the saidified remotesign payload — deliberately computed here through the real
 * {@link RemotesignRequestFactory} rather than hardcoded, because a verifier that compared the raw card
 * digest instead would pass a hardcoded fixture while failing every genuine card.
 */
@ExtendWith(MockitoExtension.class)
class AttestationImportVerifierTest {

    private static final String USER = "sub-alice";
    private static final String AID = "EWalletAid0000000000000000000000000000000";
    private static final String OOBI = "https://keria.example/oobi/" + AID + "/agent/EAgent";
    private static final String CARD_DIGEST = "ECardDigest000000000000000000000000000000";
    private static final String CREDENTIAL_SAID = "ECredentialSaid00000000000000000000000000";
    private static final String SCHEMA_SAID = "ESchemaSaid000000000000000000000000000000";
    private static final String CESR = "-CESR-credential-chain-";
    private static final String KEL_EVENT_SAID = "EKelEventSaid0000000000000000000000000000";
    private static final String KEL_SEQUENCE = "3";
    private static final String LABEL = "170";
    private static final String ISSUER_AID = "EIssuerAid00000000000000000000000000000000";

    @Mock
    private KeriOobiService oobiService;
    @Mock
    private CredentialChainValidator chainValidator;
    @Mock
    private KeriAttestationClient keriClient;
    @Mock
    private SignifyClient client;
    @Mock
    private Coring.KeyEvents keyEvents;
    @Mock
    private SchemaOobiResolver schemaOobiResolver;

    private final RemotesignRequestFactory kedFactory = new RemotesignRequestFactory();

    private AttestationImportVerifier verifier;

    @BeforeEach
    void setUp() {
        lenient().when(keriClient.client()).thenReturn(client);
        lenient().when(client.keyEvents()).thenReturn(keyEvents);
        lenient().when(oobiService.refreshResolve(any(), any(), any())).thenReturn(Either.right(null));
        lenient().when(chainValidator.validate(any(), any(), any(), any()))
                .thenReturn(Either.right(new CredentialChainValidator.ValidatedCredential(
                        CREDENTIAL_SAID, SCHEMA_SAID, "Foundation Employee", ISSUER_AID, ISSUER_AID,
                        TrustModel.STANDALONE, Map.of(), "fingerprint")));
        // Mocked: resolving configured issuer OOBIs is a precondition these tests already assume met.
        verifier = new AttestationImportVerifier(oobiService, chainValidator,
                new KelAnchorVerifier(keriClient), kedFactory, schemaOobiResolver);
    }

    /** The SAID the wallet would genuinely have sealed for this card body under this label. */
    private static String expectedPayloadSaid(String label, String cardDigest) {
        return (String) new RemotesignRequestFactory().anchorRequestKed(AID, label, cardDigest).get("d");
    }

    private void kelContains(String sequence, String eventSaid, String sealedDigest) throws Exception {
        when(keyEvents.get(AID)).thenReturn(List.of(Map.of("ked", Map.of(
                "t", "ixn", "s", sequence, "d", eventSaid,
                "a", List.of(Map.of("d", sealedDigest))))));
    }

    private AttestationImportVerifier.CardAttestationClaim claim(String label, String assertedDigest,
            String assertedPayloadSaid, String kelSequence, String kelEventSaid) {
        return new AttestationImportVerifier.CardAttestationClaim(OOBI, AID, CREDENTIAL_SAID, SCHEMA_SAID,
                kelSequence, kelEventSaid, label, assertedDigest, assertedPayloadSaid, CESR, CARD_DIGEST);
    }

    private AttestationImportVerifier.CardAttestationClaim validClaim() {
        return claim(LABEL, CARD_DIGEST, expectedPayloadSaid(LABEL, CARD_DIGEST), KEL_SEQUENCE, KEL_EVENT_SAID);
    }

    @Test
    void aCardWhoseNamedKelEventSealsTheRecomputedPayloadSaidVerifies() throws Exception {
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, expectedPayloadSaid(LABEL, CARD_DIGEST));

        assertTrue(verifier.verify(USER, validClaim()).isRight());
    }

    /**
     * The regression that motivated this path: the wallet seals the payload SAID, never the raw card
     * digest. A verifier comparing the digest would pass here — this must reject.
     */
    @Test
    void aKelEventSealingTheRawCardDigestInsteadOfThePayloadSaidIsRejected() throws Exception {
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, CARD_DIGEST);

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER, validClaim());

        assertTrue(result.isLeft());
        assertEquals(AttestationImportVerifier.CARD_ATTESTATION_INVALID, result.getLeft().getTitle());
    }

    /**
     * metadataLabel is an input to the sealed SAID. A verifier that hardcoded "170" would still pass a
     * card issued under a different label; one that reads it from the card reproduces the right SAID.
     */
    @Test
    void aCardAttestedUnderANonDefaultLabelVerifiesAgainstThatLabel() throws Exception {
        String label = "1447";
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, expectedPayloadSaid(label, CARD_DIGEST));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(label, CARD_DIGEST, expectedPayloadSaid(label, CARD_DIGEST), KEL_SEQUENCE, KEL_EVENT_SAID));

        assertTrue(result.isRight(), "a card attested under label 1447 must verify under label 1447");
        // The default-label SAID is a genuinely different value, so hardcoding 170 could not have passed.
        assertFalse(expectedPayloadSaid(label, CARD_DIGEST).equals(expectedPayloadSaid(LABEL, CARD_DIGEST)));
    }

    @Test
    void aCardWhoseLabelWasAlteredAfterAttestationIsRejected() throws Exception {
        // Sealed under 170, but the card now claims 1447.
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, expectedPayloadSaid(LABEL, CARD_DIGEST));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim("1447", CARD_DIGEST, null, KEL_SEQUENCE, KEL_EVENT_SAID));

        assertTrue(result.isLeft());
    }

    @Test
    void aCardNamingAnEventThatDoesNotExistIsRejected() throws Exception {
        kelContains("5", "EDifferentEvent", expectedPayloadSaid(LABEL, CARD_DIGEST));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(LABEL, CARD_DIGEST, null, "9", "ENotInTheKel"));

        assertTrue(result.isLeft());
        assertEquals(AttestationImportVerifier.CARD_ATTESTATION_INVALID, result.getLeft().getTitle());
    }

    /**
     * Both coordinates must name the SAME event. Matching them as alternatives — by SAID, else by
     * sequence — would accept a card pairing a real sequence with a SAID belonging to no event, and then
     * persist those false coordinates as audit provenance.
     */
    @Test
    void aCardWhoseEventSaidIsWrongIsRejectedEvenWhenTheSequenceMatches() throws Exception {
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, expectedPayloadSaid(LABEL, CARD_DIGEST));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(LABEL, CARD_DIGEST, null, KEL_SEQUENCE, "ENotTheRealEventSaid"));

        assertTrue(result.isLeft());
        assertEquals(AttestationImportVerifier.CARD_ATTESTATION_INVALID, result.getLeft().getTitle());
    }

    @Test
    void aCardWhoseSequenceIsWrongIsRejectedEvenWhenTheEventSaidMatches() throws Exception {
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, expectedPayloadSaid(LABEL, CARD_DIGEST));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(LABEL, CARD_DIGEST, null, "99", KEL_EVENT_SAID));

        assertTrue(result.isLeft());
        assertEquals(AttestationImportVerifier.CARD_ATTESTATION_INVALID, result.getLeft().getTitle());
    }

    /** A card body edited after attestation no longer digests to what the issuer signed. */
    @Test
    void aCardWhoseAssertedDigestDisagreesWithTheRecomputedOneIsRejected() throws Exception {
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(LABEL, "EAlteredDigest", null, KEL_SEQUENCE, KEL_EVENT_SAID));

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("altered"));
    }

    @Test
    void aCardWhoseAssertedPayloadSaidDisagreesWithTheDerivedOneIsRejected() throws Exception {
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(LABEL, CARD_DIGEST, "ESomeoneElsesPayloadSaid", KEL_SEQUENCE, KEL_EVENT_SAID));

        assertTrue(result.isLeft());
    }

    @Test
    void aCardNamingNoKelAnchorIsRejected() {
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(LABEL, CARD_DIGEST, null, null, null));

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("KEL anchor"));
    }

    @Test
    void aCardCarryingNoMetadataLabelIsRejectedRatherThanAssumingTheDefault() {
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                claim(null, CARD_DIGEST, null, KEL_SEQUENCE, KEL_EVENT_SAID));

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("metadataLabel"));
    }

    @Test
    void aCardCarryingNoCredentialChainIsRejected() {
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                new AttestationImportVerifier.CardAttestationClaim(OOBI, AID, CREDENTIAL_SAID, SCHEMA_SAID,
                        KEL_SEQUENCE, KEL_EVENT_SAID, LABEL, CARD_DIGEST, null, null, CARD_DIGEST));

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("credentialCesr"));
    }

    @Test
    void anUnreadableKelIsUnverifiableRatherThanInvalid() throws Exception {
        when(keyEvents.get(AID)).thenThrow(new IllegalStateException("agent down"));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER, validClaim());

        assertTrue(result.isLeft());
        assertEquals(AttestationImportVerifier.CARD_ATTESTATION_UNVERIFIABLE, result.getLeft().getTitle());
    }

    /** A hostile chain that throws must be a rejection, not a 500 escaping the import. */
    @Test
    void aCredentialChainThatThrowsIsRejectedRatherThanPropagating() {
        when(chainValidator.validate(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("malformed edge"));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER, validClaim());

        assertTrue(result.isLeft());
        assertEquals(AttestationImportVerifier.CARD_ATTESTATION_INVALID, result.getLeft().getTitle());
    }

    /**
     * The card's claimed identifiers are handed to the validator rather than re-checked here, so that
     * one place decides what a credential is. This pins the delegation: whatever the card says about
     * its credential and schema must reach the validator verbatim.
     */
    @Test
    void theCardsClaimedCredentialAndSchemaAreHandedToTheValidator() throws Exception {
        kelContains(KEL_SEQUENCE, KEL_EVENT_SAID, expectedPayloadSaid(LABEL, CARD_DIGEST));

        verifier.verify(USER, validClaim());

        verify(chainValidator).validate(CESR, AID, CREDENTIAL_SAID, SCHEMA_SAID);
    }

    @Test
    void aCardNamingNoCredentialSaidIsRejectedRatherThanSearchingTheChain() {
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result = verifier.verify(USER,
                new AttestationImportVerifier.CardAttestationClaim(OOBI, AID, null, SCHEMA_SAID,
                        KEL_SEQUENCE, KEL_EVENT_SAID, LABEL, CARD_DIGEST, null, CESR, CARD_DIGEST));

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("credentialSaid"));
    }

    /** A credential the validator rejects fails the import, whatever the card claims. */
    @Test
    void aCredentialTheValidatorRejectsFailsTheImport() {
        when(chainValidator.validate(any(), any(), any(), any())).thenReturn(Either.left(
                KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REJECTED,
                        "schema EX is not accepted")));

        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> result =
                verifier.verify(USER, validClaim());

        assertTrue(result.isLeft());
        assertTrue(result.getLeft().getDetail().contains("not accepted"));
    }

    @Test
    void anUnresolvableOobiFailsBeforeTheKelIsEverRead() {
        when(oobiService.refreshResolve(any(), any(), any()))
                .thenReturn(Either.left(ProblemDetail.forStatus(422)));

        assertTrue(verifier.verify(USER, validClaim()).isLeft());
    }
}
