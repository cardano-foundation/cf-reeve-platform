package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchemaRegistry;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.exception.SignifyInterruptedException;

/**
 * Resolves the issuer OOBIs configured on the credential-schema registry, so our agent knows the
 * registries whose TEL state verification asks about.
 *
 * <p>Without this, {@link CredentialTelStateReader} cannot answer for any issuer the agent has never
 * met, verification fails closed, and an attested card from a perfectly good issuer is rejected for
 * want of an introduction. It is the difference between the trust configuration being declarative and
 * being effective.
 *
 * <p><b>Only the CONFIGURED OOBIs are resolved</b> — never one named by the material being verified.
 * That distinction is what stops a card from introducing our agent to the very registry it wants us to
 * believe; an issuer earns a look-up by being in this deployment's configuration, not by asking.
 *
 * <p>Lazy and memoised rather than resolved at startup: boot must not depend on every configured
 * issuer being reachable. Only successful resolutions are remembered, so a transient failure stays
 * retryable instead of pinning an issuer as unreachable for the process lifetime.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class SchemaOobiResolver {

    /** Mirrors {@code KeriCredentialService}'s bound on the same underlying wait. */
    private static final long RESOLVE_TIMEOUT_MILLIS = 15_000L;

    private final KeriAttestationClient client;
    private final CredentialSchemaRegistry registry;

    private final Set<String> resolved = ConcurrentHashMap.newKeySet();

    /**
     * Resolves any configured issuer OOBI not yet successfully resolved.
     *
     * <p>Best-effort by design: a failure here is not itself a verification failure, because the
     * checks that actually depend on it fail closed on their own. Reporting it as a verification error
     * would blame the credential for the agent's connectivity.
     */
    public void ensureResolved() {
        List<String> oobis = registry.allOobis();
        for (String oobi : oobis) {
            if (oobi == null || oobi.isBlank() || resolved.contains(oobi)) {
                continue;
            }
            resolveOne(oobi);
        }
    }

    private void resolveOne(String oobi) {
        try {
            var raw = client.client().oobis().resolve(oobi, null);
            client.client().operations().wait(raw, boundedWait());
            // Reaching here means the wait completed without raising: the client now throws
            // OperationFailedException/OperationTimeoutException instead of handing back a
            // done-with-error operation, and those are caught below and left retryable.
            resolved.add(oobi);
            log.info("Resolved configured issuer OOBI {}", oobi);
        } catch (SignifyInterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not resolve configured issuer OOBI {} (will retry): {}", oobi, e.getMessage());
        }
    }

    private static Operations.WaitOptions boundedWait() {
        return Operations.WaitOptions.builder()
                .abortSignal(Operations.AbortSignal.builder().timeout(RESOLVE_TIMEOUT_MILLIS).build())
                .build();
    }
}
