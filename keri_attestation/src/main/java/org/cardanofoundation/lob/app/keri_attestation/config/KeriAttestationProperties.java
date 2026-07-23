package org.cardanofoundation.lob.app.keri_attestation.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "lob.keri-attestation")
public record KeriAttestationProperties(
        boolean enabled,
        Keria keria,
        String identifierName,
        CredentialPolicy credentialPolicy,
        @DefaultValue("PT1H") Duration ceremonyTtl,
        @DefaultValue("PT24H") Duration freezeMaxAge,
        @DefaultValue("PT3M") Duration remotesignTimeout,
        @DefaultValue("PT1.5S") Duration notificationPollInterval,
        /** DEAD CONFIG (synchronous refactor, design rev user-directed): no longer read by any code.
         *  {@code KeriAuthBeginService#submitAuthBegin}'s own-chain path used to poll (the now-removed
         *  {@code awaitAuthBeginConfirmation}) until this many confirmations were observed before
         *  completing the step; it now completes the step synchronously the moment the tx is submitted,
         *  never waiting for confirmation depth. Left in the binding (harmless if set) rather than
         *  removed, matching the "leave it, stop reading/writing it" treatment given
         *  {@code KeriAttestationCeremonyEntity#stepPhase} et al. */
        @DefaultValue("3") int authBeginConfirmations,
        Limits limits,
        /** DEAD CONFIG — see {@link #authBeginConfirmations()}'s javadoc: the poll cadence for the
         *  same now-removed confirmation-wait loop. */
        @DefaultValue("PT15S") Duration authBeginPollInterval,
        /** Still read — unlike its sibling AUTH_BEGIN properties above, NOT dead: {@code
         *  CeremonyCleanupJob#stepTimeoutBudgets} still uses this as the stale-step sweep budget for a
         *  ceremony stuck at {@code AUTH_BEGIN_SUBMITTED} (e.g. the process crashed mid-request, between
         *  submitting the tx and completing the step). */
        @DefaultValue("PT30M") Duration authBeginRollbackWindow,
        @DefaultValue("PT2S") Duration keyStateRetryInitialDelay,
        @DefaultValue("PT3S") Duration keyStateRetryInterval,
        /** Grace period added on top of a waiting step's own timeout ({@link #remotesignTimeout()}
         *  for CREDENTIAL_REQUESTED/ATTEST_REQUESTED, {@link #authBeginRollbackWindow()} for
         *  AUTH_BEGIN_SUBMITTED) before {@code CeremonyCleanupJob}'s stale-step sweep fails a
         *  ceremony stuck in that state with {@code KERI_STEP_TIMED_OUT} (design §4.2/§7). */
        @DefaultValue("PT2M") Duration stepTimeoutGrace,
        Executor executor) {

    // Spring's ValueObjectBinder only instantiates a nested record when at least one of its own
    // properties is present in a property source; @DefaultValue alone won't trigger construction
    // of `limits` (or `executor`) when the whole `lob.keri-attestation.limits.*` (or `.executor.*`)
    // section is absent. Normalize here so callers always see the documented defaults instead of a
    // null Limits/Executor.
    public KeriAttestationProperties {
        if (limits == null) {
            limits = new Limits(3, Duration.parse("PT10S"));
        }
        if (executor == null) {
            executor = new Executor(4, 2);
        }
    }

    public record Keria(String url, String bootUrl, String bran) {
    }

    /**
     * {@code schemaBaseUrl} (live-testing fix): the credential SCHEMA SERVER's base OOBI URL — e.g.
     * {@code https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi}, no trailing slash. Two
     * independent uses in {@code KeriCredentialService}: (1) the IPEX apply's top-level {@code oobiUrl}
     * (with a trailing slash appended), which is where a Veridian-style wallet actually resolves the
     * credential schema from — NOT our agent's own OOBI; (2) as the base for resolving each of
     * {@link #schemaSaids()} as an OOBI on OUR OWN agent ({@code baseUrl + "/" + said}) before ever
     * sending an apply — KERIA silently drops an exchange referencing a schema SAID the receiving
     * agent has never itself resolved.
     */
    public record CredentialPolicy(
            List<String> schemaSaids,
            List<String> trustedRootAids,
            @DefaultValue("https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi") String schemaBaseUrl) {
    }

    public record Limits(
            @DefaultValue("3") int maxActiveCeremoniesPerUser,
            @DefaultValue("PT10S") Duration stepCooldown) {
    }

    /**
     * DEAD CONFIG (synchronous refactor, design rev user-directed): pool sizes for the module's two
     * dedicated async executors, {@code keriAttestationExecutor} and {@code
     * keriAttestationConfirmationExecutor} — both defined by the now-deleted {@code
     * KeriAttestationAsyncConfig}, dispatched to by the now-deleted {@code CeremonyAsyncRunner}. The
     * wallet-interaction flow (credential presentation, ATTEST, AUTH_BEGIN) now runs entirely on the
     * original request thread; there is no background executor left for either pool size to configure.
     * Left in the binding (harmless if set) rather than removed — see {@link
     * #authBeginConfirmations()}'s javadoc for the same treatment elsewhere in this record.
     */
    public record Executor(
            @DefaultValue("4") int walletPoolSize,
            @DefaultValue("2") int confirmationPoolSize) {
    }
}
