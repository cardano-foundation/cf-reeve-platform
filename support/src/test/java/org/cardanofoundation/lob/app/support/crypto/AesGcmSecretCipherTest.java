package org.cardanofoundation.lob.app.support.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class AesGcmSecretCipherTest {

    private static final String KEY = randomKey();

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);

        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void roundTripsPlaintext() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);
        String secret = "-----BEGIN PRIVATE KEY-----\nMIIEvQ==\n-----END PRIVATE KEY-----";

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void prefixesEnvelopeWithVersion() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);

        assertThat(cipher.encrypt("x")).startsWith("v1:");
    }

    @Test
    void producesADifferentEnvelopeEachTimeSoIvsAreNotReused() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);
        String envelope = cipher.encrypt("secret");
        char[] chars = envelope.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> cipher.decrypt(new String(chars)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotDecryptWithADifferentKey() {
        String envelope = new AesGcmSecretCipher(KEY).encrypt("secret");

        assertThatThrownBy(() -> new AesGcmSecretCipher(randomKey()).decrypt(envelope))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnUnknownEnvelopeVersion() {
        SecretCipher cipher = new AesGcmSecretCipher(KEY);

        assertThatThrownBy(() -> cipher.decrypt("v2:AAAA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmSecretCipher(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsAMissingKey() {
        assertThatThrownBy(() -> new AesGcmSecretCipher("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
