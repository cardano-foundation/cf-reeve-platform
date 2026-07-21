package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.config.KeriAttestationModuleConfig;

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
}
