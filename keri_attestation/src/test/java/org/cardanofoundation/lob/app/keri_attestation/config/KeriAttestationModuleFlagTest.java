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
