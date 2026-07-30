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
        /** The schemas this deployment accepts and who may issue them — see
         *  {@link CredentialSchemaRegistry}, which validates this at startup. Supersedes the schema and
         *  trust-root fields of {@link CredentialPolicy}. */
        List<CredentialSchema> credentialSchemas,
        @DefaultValue("PT1H") Duration ceremonyTtl,
        @DefaultValue("PT24H") Duration freezeMaxAge,
        @DefaultValue("PT3M") Duration remotesignTimeout,
        @DefaultValue("PT1.5S") Duration notificationPollInterval,
        /** Unused. AUTH_BEGIN completes the moment the tx is submitted and no longer waits for a
         *  confirmation depth. Kept in the binding so existing configuration still loads. */
        @DefaultValue("3") int authBeginConfirmations,
        Limits limits,
        /** Unused: the poll cadence of the removed confirmation wait. See
         *  {@link #authBeginConfirmations()}. */
        @DefaultValue("PT15S") Duration authBeginPollInterval,
        /** Unlike the two AUTH_BEGIN properties above, this one is read: it is
         *  {@code CeremonyCleanupJob}'s stale-step budget for a ceremony stuck at
         *  {@code AUTH_BEGIN_SUBMITTED}, e.g. after a crash between submitting the tx and completing
         *  the step. */
        @DefaultValue("PT30M") Duration authBeginRollbackWindow,
        @DefaultValue("PT2S") Duration keyStateRetryInitialDelay,
        @DefaultValue("PT3S") Duration keyStateRetryInterval,
        /** Grace period added on top of a waiting step's own timeout ({@link #remotesignTimeout()}
         *  for CREDENTIAL_REQUESTED/ATTEST_REQUESTED, {@link #authBeginRollbackWindow()} for
         *  AUTH_BEGIN_SUBMITTED) before {@code CeremonyCleanupJob}'s stale-step sweep fails a
         *  ceremony stuck in that state with {@code KERI_STEP_TIMED_OUT}. */
        @DefaultValue("PT2M") Duration stepTimeoutGrace,
        Executor executor) {

    // Spring's ValueObjectBinder only instantiates a nested record when at least one of its own
    // properties is present in a property source, so @DefaultValue alone does not construct `limits`
    // or `executor` when their whole section is absent. Normalise here so callers always see the
    // documented defaults rather than null.
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
     * {@code schemaBaseUrl} is the credential schema server's base OOBI URL, without a trailing slash.
     * {@code KeriCredentialService} uses it twice: as the IPEX apply's top-level {@code oobiUrl}, which
     * is where the wallet resolves the schema from — not our agent's own OOBI — and as the base for
     * resolving each accepted schema SAID on our own agent before sending an apply, since KERIA
     * silently drops an exchange referencing a schema SAID the receiving agent has never resolved.
     *
     * <p>{@code schemaSaids} and {@code trustedRootAids} are <b>deprecated</b> in favour of
     * {@link KeriAttestationProperties#credentialSchemas()}, which says which trust model each schema
     * uses instead of assuming one for all of them. They are still read for one release — see
     * {@link CredentialSchemaRegistry} — and then removed. {@code schemaBaseUrl} is not deprecated.
     */
    public record CredentialPolicy(
            @Deprecated List<String> schemaSaids,
            @Deprecated List<String> trustedRootAids,
            @DefaultValue("https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi") String schemaBaseUrl) {
    }

    public record Limits(
            @DefaultValue("3") int maxActiveCeremoniesPerUser,
            @DefaultValue("PT10S") Duration stepCooldown) {
    }

    /**
     * Unused. The wallet-interaction flow — credential presentation, ATTEST, AUTH_BEGIN — runs on the
     * request thread, so there is no background executor left for these pool sizes to configure. Kept
     * in the binding so existing configuration still loads.
     */
    public record Executor(
            @DefaultValue("4") int walletPoolSize,
            @DefaultValue("2") int confirmationPoolSize) {
    }
}
