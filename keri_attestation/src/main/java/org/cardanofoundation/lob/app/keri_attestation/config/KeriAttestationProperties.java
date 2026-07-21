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
        @DefaultValue("3") int authBeginConfirmations,
        Limits limits,
        @DefaultValue("PT15S") Duration authBeginPollInterval,
        @DefaultValue("PT30M") Duration authBeginRollbackWindow,
        @DefaultValue("PT2S") Duration keyStateRetryInitialDelay,
        @DefaultValue("PT3S") Duration keyStateRetryInterval) {

    // Spring's ValueObjectBinder only instantiates a nested record when at least one of its own
    // properties is present in a property source; @DefaultValue alone won't trigger construction
    // of `limits` when the whole `lob.keri-attestation.limits.*` section is absent. Normalize here
    // so callers always see the documented defaults instead of a null Limits.
    public KeriAttestationProperties {
        if (limits == null) {
            limits = new Limits(3, Duration.parse("PT10S"));
        }
    }

    public record Keria(String url, String bootUrl, String bran) {
    }

    public record CredentialPolicy(List<String> schemaSaids, List<String> trustedRootAids) {
    }

    public record Limits(
            @DefaultValue("3") int maxActiveCeremoniesPerUser,
            @DefaultValue("PT10S") Duration stepCooldown) {
    }
}
