package org.cardanofoundation.lob.app.keri_attestation.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CeremonyService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAgentService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAuthBeginService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriCredentialService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriOobiService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Pins {@link KeriAttestationController}'s {@code @ConditionalOnProperty} gate — deliberately the
 * narrower {@code lob.keri-attestation.keria.url}, not the module's own {@code enabled} flag, exactly
 * like every one of its collaborator services (see the controller's own javadoc, and
 * {@code SignifyClientConfig}'s, for why). This matters concretely: {@code CeremonyRepositoryTest}
 * component-scans this whole package with {@code enabled=true} but no {@code keria.url} configured —
 * without this gate, Spring would try to construct this controller there too and fail on its five
 * keria-conditional service dependencies, none of which exist in that context.
 */
class KeriAttestationControllerConditionalTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KeriAttestationController.class)
            .withBean(KeycloakSecurityHelper.class, () -> mock(KeycloakSecurityHelper.class))
            .withBean(CeremonyService.class, () -> mock(CeremonyService.class))
            .withBean(KeriOobiService.class, () -> mock(KeriOobiService.class))
            .withBean(KeriAgentService.class, () -> mock(KeriAgentService.class))
            .withBean(KeriCredentialService.class, () -> mock(KeriCredentialService.class))
            .withBean(KeriAuthBeginService.class, () -> mock(KeriAuthBeginService.class))
            .withBean(KeriAttestService.class, () -> mock(KeriAttestService.class))
            .withBean(KeriIdentityLinkRepository.class, () -> mock(KeriIdentityLinkRepository.class));

    @Test
    void absentWhenKeriaUrlIsNotConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KeriAttestationController.class);
        });
    }

    @Test
    void presentWhenKeriaUrlIsConfigured() {
        contextRunner.withPropertyValues("lob.keri-attestation.keria.url=https://example.org").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KeriAttestationController.class);
        });
    }
}
