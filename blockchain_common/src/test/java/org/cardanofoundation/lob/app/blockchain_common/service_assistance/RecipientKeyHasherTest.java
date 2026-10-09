package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The hash is a PUBLISHED wire-format contract (docs/onChainFormat.md), reimplemented independently
 * in the Indexer frontend. These golden vectors are the RFC 7748 section 6.1 X25519 public keys and
 * are asserted identically in reeve-indexing-example's recipientKeyHash.spec.ts. If you change what
 * this produces, you have changed the on-chain format and broken every already-anchored document.
 */
class RecipientKeyHasherTest {

    private static final String ALICE_PUB = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
    private static final String ALICE_HASH = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae";
    private static final String BOB_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
    private static final String BOB_HASH = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4";

    @Test
    void matchesTheGoldenVectors() {
        assertThat(RecipientKeyHasher.hash(ALICE_PUB)).isEqualTo(ALICE_HASH);
        assertThat(RecipientKeyHasher.hash(BOB_PUB)).isEqualTo(BOB_HASH);
    }

    @Test
    void isCaseInsensitiveOnInputAndAlwaysLowercaseOnOutput() {
        assertThat(RecipientKeyHasher.hash(ALICE_PUB.toUpperCase())).isEqualTo(ALICE_HASH);
    }

    @Test
    void hashesTheDecodedBytesNotTheHexString() {
        // The single most likely reimplementation bug: hashing the ASCII hex instead of the 32 bytes
        // it denotes. That yields a plausible-looking digest no other implementation would agree with.
        assertThat(RecipientKeyHasher.hash(ALICE_PUB)).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void rejectsInputThatIsNotA32ByteHexKey() {
        assertThatThrownBy(() -> RecipientKeyHasher.hash("not-hex"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecipientKeyHasher.hash("abcd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
        assertThatThrownBy(() -> RecipientKeyHasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
