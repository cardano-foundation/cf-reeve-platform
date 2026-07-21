package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

/**
 * A focused Spring-wiring smoke test for the {@link KeriAttestService} &lt;-&gt;
 * {@link CeremonyAsyncRunner} &lt;-&gt; {@link KeriAuthBeginService} constructor-injection cycle (see
 * {@link CeremonyAsyncRunner}'s javadoc): proves the {@code @Lazy} break actually resolves at
 * context-refresh time, not just that the classes compile. Every collaborator beyond the four classes
 * under test is a bare Mockito mock — this test asserts wiring succeeds, not behavior (behavior is
 * covered by each class's own unit tests). Beans are registered directly (no component scan), so this
 * never touches {@code SignifyClientConfig}'s real, network-connecting bean.
 */
class CeremonyAsyncRunnerWiringTest {

    @Test
    void keriAttestServiceKeriAuthBeginServiceAndCeremonyAsyncRunnerWireUpWithoutACircularDependencyFailure() {
        new ApplicationContextRunner()
                // KeriAttestService/KeriAuthBeginService/CeremonyAsyncRunner are all
                // @ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url") — without
                // this, their bean definitions are skipped by the condition evaluator and
                // context.getBean(CeremonyAsyncRunner.class) throws NoSuchBeanDefinitionException
                // (nothing to do with the @Lazy cycle this test actually targets).
                .withPropertyValues("lob.keri-attestation.keria.url=https://example.org")
                .withBean("keriAttestationSignifyClient", SignifyClient.class, () -> mock(SignifyClient.class))
                .withBean(KeriAttestationProperties.class, CeremonyAsyncRunnerWiringTest::properties)
                .withBean(KeriAgentService.class, () -> mock(KeriAgentService.class))
                .withBean(RemotesignRequestFactory.class, () -> mock(RemotesignRequestFactory.class))
                .withBean(AttestationTargetProviderRegistry.class, () -> new AttestationTargetProviderRegistry(List.of()))
                .withBean(KeriNotificationCorrelator.class, () -> mock(KeriNotificationCorrelator.class))
                .withBean(CeremonyService.class, () -> mock(CeremonyService.class))
                .withBean(KeriAttestationCeremonyRepository.class, () -> mock(KeriAttestationCeremonyRepository.class))
                .withBean(KeriIdentityLinkRepository.class, () -> mock(KeriIdentityLinkRepository.class))
                .withBean(CesrChainReducer.class, () -> mock(CesrChainReducer.class))
                .withBean(Cip170MetadataFactory.class, () -> mock(Cip170MetadataFactory.class))
                .withBean(CardanoMetadataTxSubmitter.class, () -> mock(CardanoMetadataTxSubmitter.class))
                .withBean(CredentialChainValidator.class, () -> mock(CredentialChainValidator.class))
                .withBean(KeriCredentialService.class)
                .withBean(KeriAttestService.class)
                .withBean(KeriAuthBeginService.class)
                .withBean(CeremonyAsyncRunner.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CeremonyAsyncRunner.class)).isNotNull();
                    assertThat(context.getBean(KeriAttestService.class)).isNotNull();
                    assertThat(context.getBean(KeriAuthBeginService.class)).isNotNull();
                });
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT1.5S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), null);
    }
}
