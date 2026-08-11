package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.signify.app.aiding.IdentifierController;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.generated.keria.model.HabState;
import org.cardanofoundation.signify.generated.keria.model.KeyStateRecord;

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

    // ==================== agent witness diagnostics ====================

    private static ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(SignifyClientConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        return appender;
    }

    private static SignifyClient clientWithWitnesses(List<String> witnesses) throws Exception {
        KeyStateRecord state = mock(KeyStateRecord.class);
        when(state.getB()).thenReturn(witnesses);
        HabState hab = mock(HabState.class);
        when(hab.getState()).thenReturn(state);

        IdentifierController identifiers = mock(IdentifierController.class);
        when(identifiers.get("agent")).thenReturn(Optional.of(hab));
        SignifyClient client = mock(SignifyClient.class);
        when(client.identifiers()).thenReturn(identifiers);

        return client;
    }

    /**
     * The failure this guards against: an identifier created before witnesses were configured is
     * reused verbatim at startup, so it stays witness-less and inbound IPEX silently never arrives —
     * the wallet reports success and the backend sees nothing. Nothing used to say so.
     */
    @Test
    void warnsWhenTheReusedAgentAidHasNoWitnesses() throws Exception {
        ListAppender<ILoggingEvent> logs = captureLogs();

        SignifyClientConfig.warnIfNoWitnesses(clientWithWitnesses(List.of()), "agent", "EAGENTPREFIX");

        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("NO WITNESSES")));
    }

    @Test
    void logsTheWitnessSetWhenTheAgentAidHasOne() throws Exception {
        ListAppender<ILoggingEvent> logs = captureLogs();

        SignifyClientConfig.warnIfNoWitnesses(clientWithWitnesses(List.of("BWITNESS1")), "agent", "EAGENTPREFIX");

        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN));
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("BWITNESS1")));
    }
}
