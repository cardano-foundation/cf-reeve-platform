package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SignifyClientConfig#resolveBran}: a configured passcode must be honored
 * verbatim (so the agent identity is STABLE across restarts and existing Veridian wallet pairings keep
 * receiving notifications); an empty one falls back to an ephemeral random passcode.
 */
class SignifyClientConfigTest {

    @Test
    void resolveBranReturnsAConfiguredPasscodeVerbatim() {
        // The crux of the fix: a stable configured bran is used as-is, never randomized — this is what
        // keeps the agent AID (and thus the wallet pairing) stable across restarts.
        assertEquals("0A98gEaYge5XlagRM9okY", SignifyClientConfig.resolveBran("0A98gEaYge5XlagRM9okY"));
    }

    @Test
    void resolveBranFallsBackToARandomPasscodeWhenNull() {
        String bran = SignifyClientConfig.resolveBran(null);
        assertNotNull(bran);
        assertFalse(bran.isEmpty());
    }

    @Test
    void resolveBranFallsBackToARandomPasscodeWhenEmpty() {
        String bran = SignifyClientConfig.resolveBran("");
        assertNotNull(bran);
        assertFalse(bran.isEmpty());
    }

    @Test
    void resolveBranFallbackIsEphemeralWhichIsWhyAnEmptyBranBreaksPairings() {
        // Two empty-bran resolutions yield DIFFERENT passcodes — i.e. a different agent identity each
        // time — which is exactly the restart-rotation that broke inbound wallet notifications.
        assertNotEquals(SignifyClientConfig.resolveBran(""), SignifyClientConfig.resolveBran(""));
    }
}
