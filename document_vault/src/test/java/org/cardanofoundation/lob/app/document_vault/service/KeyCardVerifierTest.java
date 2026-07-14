package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

class KeyCardVerifierTest {

    /** Fixed seed -> deterministic issuer keypair, so this test has no hidden randomness. */
    private static final byte[] SEED = HexFormat.of()
            .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final String ISSUER_ID = "reeve-indexer-test";
    private static final String X25519_PUB = "a".repeat(64);

    private Ed25519PrivateKeyParameters issuerPriv;
    private String issuerPubHex;
    private KeyCardVerifier verifier;

    @BeforeEach
    void setUp() {
        issuerPriv = new Ed25519PrivateKeyParameters(SEED, 0);
        issuerPubHex = HexFormat.of().formatHex(issuerPriv.generatePublicKey().getEncoded());
        verifier = new KeyCardVerifier(ISSUER_ID + ":" + issuerPubHex);
    }

    private KeyCardDto card() {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Bob Miller", "bob@example.org", "org1"));
        card.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        card.setIssuer(new KeyCardDto.Issuer(ISSUER_ID, "Ed25519", issuerPubHex));
        card.setSignature(sign(card));
        return card;
    }

    private String sign(KeyCardDto card) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, issuerPriv);
        byte[] input = KeyCardVerifier.signingInput(card);
        signer.update(input, 0, input.length);
        return HexFormat.of().formatHex(signer.generateSignature());
    }

    @Test
    void acceptsAGenuineCard() {
        assertTrue(verifier.verify(card(), "org1").isRight());
    }

    /**
     * The signing input, built independently from contract §2.8.3: 14 length-prefixed UTF-8 fields,
     * each preceded by its 4-byte big-endian length, in exactly this order.
     */
    @Test
    void signingInputIsLengthPrefixedInTheContractOrder() {
        byte[] expected = concat(
                lp("REEVE_KEY_CARD"), lp("1"),
                lp("REEVE_ACCOUNT"), lp("sub-bob"), lp("Bob Miller"), lp("bob@example.org"), lp("org1"),
                lp(X25519_PUB), lp("Bob's audit key"), lp("PORTABLE"), lp("2026-07-14T10:15:30Z"),
                lp(ISSUER_ID), lp("Ed25519"), lp(issuerPubHex));

        assertArrayEquals(expected, KeyCardVerifier.signingInput(card()));
    }

    @Test
    void rejectsAnUnknownIssuer() {
        KeyCardDto card = card();
        card.setIssuer(new KeyCardDto.Issuer("someone-else", "Ed25519", "b".repeat(64)));

        Either<ProblemDetail, KeyCardDto> result = verifier.verify(card, "org1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_ISSUER_UNKNOWN, result.getLeft().getTitle());
    }

    /** An issuer id we know, but paired with a public key we do not: the pairing itself is checked. */
    @Test
    void rejectsAKnownIssuerIdCarryingAForeignPublicKey() {
        KeyCardDto card = card();
        card.setIssuer(new KeyCardDto.Issuer(ISSUER_ID, "Ed25519", "b".repeat(64)));

        assertEquals(VaultProblems.CARD_ISSUER_UNKNOWN, verifier.verify(card, "org1").getLeft().getTitle());
    }

    /** Every signed field must really be covered — tamper with each in turn, all must fail. */
    @Test
    void rejectsTamperingWithAnySignedField() {
        KeyCardDto tamperedKey = card();
        tamperedKey.setKey(new KeyCardDto.Key("b".repeat(64), "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedKey, "org1").getLeft().getTitle());

        KeyCardDto tamperedSubject = card();
        tamperedSubject.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-mallory",
                "Bob Miller", "bob@example.org", "org1"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedSubject, "org1").getLeft().getTitle());

        KeyCardDto tamperedEmail = card();
        tamperedEmail.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Bob Miller", "mallory@example.org", "org1"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedEmail, "org1").getLeft().getTitle());

        KeyCardDto tamperedAssurance = card();
        tamperedAssurance.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PASSKEY,
                "2026-07-14T10:15:30Z"));
        assertEquals(VaultProblems.CARD_SIGNATURE_INVALID,
                verifier.verify(tamperedAssurance, "org1").getLeft().getTitle());
    }

    @Test
    void rejectsACardIssuedForAnotherOrganisation() {
        assertEquals(VaultProblems.CARD_ORG_MISMATCH,
                verifier.verify(card(), "other-org").getLeft().getTitle());
    }

    @Test
    void rejectsAnUnsupportedCardVersion() {
        KeyCardDto card = card();
        card.setV(2);

        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION,
                verifier.verify(card, "org1").getLeft().getTitle());
    }

    /** A card must not be able to claim one algorithm while we verify it under another. */
    @Test
    void rejectsAnAlgorithmOtherThanEd25519() {
        KeyCardDto card = card();
        card.setIssuer(new KeyCardDto.Issuer(ISSUER_ID, "RSA", issuerPubHex));

        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION,
                verifier.verify(card, "org1").getLeft().getTitle());
    }

    /** I5: a private key must never enter the backend — not even wrapped. Reject, never silently drop. */
    @Test
    void rejectsACardStillCarryingItsPrivateKeySection() {
        KeyCardDto card = card();
        card.putUnknown("privateKey", java.util.Map.of("wrapped", "deadbeef"));

        assertEquals(VaultProblems.CARD_CONTAINS_PRIVATE_KEY,
                verifier.verify(card, "org1").getLeft().getTitle());
    }

    @Test
    void reportsWhenNoIssuersAreConfigured() {
        assertFalse(new KeyCardVerifier("").hasIssuers());
        assertTrue(verifier.hasIssuers());
    }

    // --- test-local helpers: an independent implementation of §2.8.3 ---

    private static byte[] lp(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return concat(ByteBuffer.allocate(4).putInt(bytes.length).array(), bytes);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
