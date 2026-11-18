package org.cardanofoundation.lob.app.support.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RsaOaepHybridCipherTest {

    private static final String ORG_ID = "75f95560c1d2de4b4ea0";
    /** Shaped like the real payload: a multi-line, multi-KB PEM. */
    private static final String PLAINTEXT =
            "-----BEGIN PRIVATE KEY-----\n" + "MIIJQgIBADANBgkqhkiG9w0BAQE\n".repeat(120) + "-----END PRIVATE KEY-----\n";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static String publicKeyB64;
    private static String privateKeyB64;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
        publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private static RsaOaepHybridEncryptor encryptor() {
        return new RsaOaepHybridEncryptor(publicKeyB64);
    }

    private static RsaOaepHybridDecryptor decryptor() {
        return new RsaOaepHybridDecryptor(privateKeyB64);
    }

    @Test
    void roundtripsAMultiKilobytePem() {
        String envelope = encryptor().encrypt(PLAINTEXT, ORG_ID);

        assertThat(decryptor().decrypt(envelope, ORG_ID)).isEqualTo(PLAINTEXT);
    }

    @Test
    void producesAFreshEnvelopePerCall() {
        RsaOaepHybridEncryptor enc = encryptor();

        assertThat(enc.encrypt(PLAINTEXT, ORG_ID)).isNotEqualTo(enc.encrypt(PLAINTEXT, ORG_ID));
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] raw = Base64.getDecoder().decode(encryptor().encrypt(PLAINTEXT, ORG_ID));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> decryptor().decrypt(tampered, ORG_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tampered or organisation context mismatch");
    }

    @Test
    void rejectsDecryptionUnderADifferentOrganisation() {
        String envelope = encryptor().encrypt(PLAINTEXT, ORG_ID);

        assertThatThrownBy(() -> decryptor().decrypt(envelope, "another-org"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tampered or organisation context mismatch");
    }

    @Test
    void rejectsTheWrongPrivateKeyAsAKeyPairMismatch() {
        String envelope = encryptor().encrypt(PLAINTEXT, ORG_ID);
        RsaOaepHybridDecryptor wrongKey = new RsaOaepHybridDecryptor(
                Base64.getEncoder().encodeToString(otherKeyPair.getPrivate().getEncoded()));

        assertThatThrownBy(() -> wrongKey.decrypt(envelope, ORG_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-pair mismatch");
    }

    @Test
    void rejectsEmptyEnvelopes() {
        for (String envelope : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> decryptor().decrypt(envelope, ORG_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }
    }

    @Test
    void rejectsTruncatedAndGarbageEnvelopesWithoutIndexErrors() {
        RsaOaepHybridDecryptor dec = decryptor();
        // A plausible-looking length field (exactly the 3072-bit modulus size, 384 bytes) with a
        // body too short to hold the declared key block — exercises the body-length bound, which
        // no earlier check reaches.
        ByteBuffer shortBody = ByteBuffer.allocate(2 + 384 + 10);
        shortBody.putShort((short) 384);
        shortBody.put(new byte[384 + 10 - 2]);
        // Correct length and a full key block, but no room for IV + tag.
        ByteBuffer missingIvAndTag = ByteBuffer.allocate(2 + 384);
        missingIvAndTag.putShort((short) 384);
        missingIvAndTag.put(new byte[384]);
        String[] malformed = {
                "AA==",                                              // 1 byte: no length field
                Base64.getEncoder().encodeToString(new byte[]{0, 0, 1, 2, 3}), // len == 0
                Base64.getEncoder().encodeToString(new byte[]{(byte) 0xFF, (byte) 0xFF, 1, 2, 3}), // len beyond data
                Base64.getEncoder().encodeToString(shortBody.array()),
                Base64.getEncoder().encodeToString(missingIvAndTag.array()),
                "%%%not-base64%%%",
        };

        for (String envelope : malformed) {
            assertThatThrownBy(() -> dec.decrypt(envelope, ORG_ID))
                    .describedAs("envelope %s", envelope)
                    .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class)
                    .isNotInstanceOf(ArrayIndexOutOfBoundsException.class)
                    .isNotInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    /**
     * Interop pin: an envelope whose data key was wrapped by a FOREIGN implementation using
     * explicit OAEP(SHA-256, MGF1-SHA256) must decrypt. Guards against the SunJCE default of
     * MGF1-SHA1 sneaking into either side.
     */
    @Test
    void decryptsAnEnvelopeWrappedWithExplicitOaepSha256Mgf1Sha256() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] dataKey = new byte[32];
        byte[] iv = new byte[12];
        random.nextBytes(dataKey);
        random.nextBytes(iv);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(("reeve/netsuite.privateKey/" + ORG_ID).getBytes(UTF_8));
        byte[] ciphertext = aes.doFinal(PLAINTEXT.getBytes(UTF_8));

        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, keyPair.getPublic(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] wrapped = rsa.doFinal(dataKey);

        ByteBuffer buffer = ByteBuffer.allocate(2 + wrapped.length + iv.length + ciphertext.length);
        buffer.putShort((short) wrapped.length);
        buffer.put(wrapped);
        buffer.put(iv);
        buffer.put(ciphertext);
        String envelope = Base64.getEncoder().encodeToString(buffer.array());

        assertThat(decryptor().decrypt(envelope, ORG_ID)).isEqualTo(PLAINTEXT);
    }

    @Test
    void rejectsKeysBelow3072Bits() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair weak = generator.generateKeyPair();

        assertThatThrownBy(() -> new RsaOaepHybridEncryptor(
                Base64.getEncoder().encodeToString(weak.getPublic().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3072")
                .hasMessageContaining("public key");

        assertThatThrownBy(() -> new RsaOaepHybridDecryptor(
                Base64.getEncoder().encodeToString(weak.getPrivate().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3072")
                .hasMessageContaining("private key");
    }

    @Test
    void toleratesWhitespaceAndLineBreaksInKeyEnvValues() {
        String wrapped = publicKeyB64.replaceAll("(.{64})", "$1\n") + " \n";

        String envelope = new RsaOaepHybridEncryptor(wrapped).encrypt(PLAINTEXT, ORG_ID);

        assertThat(decryptor().decrypt(envelope, ORG_ID)).isEqualTo(PLAINTEXT);
    }

    @Test
    void rejectsNonRsaKeys() throws Exception {
        KeyPair ec = KeyPairGenerator.getInstance("EC").generateKeyPair();

        assertThatThrownBy(() -> new RsaOaepHybridEncryptor(
                Base64.getEncoder().encodeToString(ec.getPublic().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key is not a valid X.509 RSA key");
    }

    @Test
    void rejectsBlankOrInvalidKeysNamingTheKeyRole() {
        assertThatThrownBy(() -> new RsaOaepHybridEncryptor(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public key is not set");

        assertThatThrownBy(() -> new RsaOaepHybridDecryptor(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private key is not set");

        assertThatThrownBy(() -> new RsaOaepHybridDecryptor("%%%"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private key is not valid Base64");
    }

}
