package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.config.KeriAttestationModuleConfig;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

class KeriAttestationModuleFlagTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(KeriAttestationModuleConfig.class);

    @Test
    void propertyUnset_moduleStaysOff() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KeriAttestationModuleConfig.class);
            assertThat(context).doesNotHaveBean(KeriAttestationProperties.class);
        });
    }

    @Test
    void propertyExplicitlyFalse_moduleStaysOff() {
        contextRunner.withPropertyValues("lob.keri-attestation.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KeriAttestationModuleConfig.class);
            assertThat(context).doesNotHaveBean(KeriAttestationProperties.class);
        });
    }

    @Test
    void propertyTrue_moduleBindsDefaultProperties() {
        contextRunner.withPropertyValues("lob.keri-attestation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KeriAttestationModuleConfig.class);
            assertThat(context).hasSingleBean(KeriAttestationProperties.class);

            KeriAttestationProperties properties = context.getBean(KeriAttestationProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.ceremonyTtl()).isEqualTo(Duration.parse("PT1H"));
            assertThat(properties.freezeMaxAge()).isEqualTo(Duration.parse("PT24H"));
            assertThat(properties.remotesignTimeout()).isEqualTo(Duration.parse("PT3M"));
            assertThat(properties.notificationPollInterval()).isEqualTo(Duration.parse("PT1.5S"));
            assertThat(properties.authBeginConfirmations()).isEqualTo(3);
            assertThat(properties.limits().maxActiveCeremoniesPerUser()).isEqualTo(3);
            assertThat(properties.limits().stepCooldown()).isEqualTo(Duration.parse("PT10S"));
            assertThat(properties.stepTimeoutGrace()).isEqualTo(Duration.parse("PT2M"));
            assertThat(properties.executor().walletPoolSize()).isEqualTo(4);
            assertThat(properties.executor().confirmationPoolSize()).isEqualTo(2);
        });
    }

    /** Live-testing fix: adding {@code @DefaultValue} to {@code CredentialPolicy}'s third field must
     *  NOT change Spring's "construct only if at least one own property is present" threshold for the
     *  whole nested record -- a fully-absent {@code credential-policy} block must still leave
     *  {@code credentialPolicy()} {@code null}, exactly as it did with the original two-field record
     *  (mirrors code-review verification requested for this change). */
    @Test
    void credentialPolicy_fullyAbsentBlockStaysNull() {
        contextRunner.withPropertyValues("lob.keri-attestation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            KeriAttestationProperties properties = context.getBean(KeriAttestationProperties.class);
            assertThat(properties.credentialPolicy()).isNull();
        });
    }

    /** Live-testing fix: {@code CredentialPolicy#schemaBaseUrl} must bind to its documented default
     *  when the property itself is absent (as long as the enclosing {@code credential-policy} block is
     *  constructed at all -- forced here via {@code schema-saids}, since Spring's {@code
     *  ValueObjectBinder} only instantiates a nested record when at least one of its own properties is
     *  present). */
    @Test
    void credentialPolicySchemaBaseUrl_defaultsWhenUnset() {
        contextRunner.withPropertyValues(
                "lob.keri-attestation.enabled=true",
                "lob.keri-attestation.credential-policy.schema-saids[0]=ESCHEMA00000000000000000000000000000000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KeriAttestationProperties properties = context.getBean(KeriAttestationProperties.class);
                    assertThat(properties.credentialPolicy().schemaBaseUrl())
                            .isEqualTo("https://cred-issuance.demo.idw-sandboxes.cf-deployments.org/oobi");
                });
    }

    /** Live-testing fix: an explicitly configured {@code schema-base-url} overrides the default. */
    @Test
    void credentialPolicySchemaBaseUrl_explicitValueBinds() {
        contextRunner.withPropertyValues(
                "lob.keri-attestation.enabled=true",
                "lob.keri-attestation.credential-policy.schema-saids[0]=ESCHEMA00000000000000000000000000000000",
                "lob.keri-attestation.credential-policy.schema-base-url=https://custom.example.org/oobi")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    KeriAttestationProperties properties = context.getBean(KeriAttestationProperties.class);
                    assertThat(properties.credentialPolicy().schemaBaseUrl())
                            .isEqualTo("https://custom.example.org/oobi");
                });
    }

    /**
     * F1 fix: this module must never expose a bare {@link SignifyClient} bean — legacy
     * {@code blockchain_publisher} (a module this now depends on it) injects an <em>unqualified</em>
     * {@code SignifyClient} of its own, and a second unqualified bean of the same type would make an
     * application context wiring both modules together fail at startup with
     * {@code NoUniqueBeanDefinitionException}. With the module enabled (component-scanning every class
     * in the package, {@code SignifyClientConfig} included) but no {@code lob.keri-attestation.keria.url}
     * configured, {@code SignifyClientConfig}'s own narrower conditional gate keeps its {@code @Bean}
     * methods from registering at all — the same precondition {@code CeremonyRepositoryTest} relies on
     * to avoid a live KERIA connection during this test — so this assertion holds without a real KERIA
     * agent. {@link KeriAttestationClient} is the only bean this module ever exposes for KERIA access.
     */
    @Test
    void moduleEnabled_exposesNoBareSignifyClientBean() {
        contextRunner.withPropertyValues("lob.keri-attestation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SignifyClient.class);
        });
    }
}
