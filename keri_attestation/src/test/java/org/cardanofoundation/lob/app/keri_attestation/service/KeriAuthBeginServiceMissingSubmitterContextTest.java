package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;

/**
 * {@code KeriAuthBeginService} constructor-required {@link CardanoMetadataTxSubmitter}, an
 * interface only {@code blockchain_publisher} implements — no implementation exists unless that module
 * is also enabled. Since it depends on this module (not the other way around), that hard dependency
 * broke {@code keri_attestation} startup whenever it was enabled without {@code blockchain_publisher}.
 *
 * <p>Proves the fix directly: every other collaborator is registered as a bare Mockito mock, and —
 * deliberately — no {@link CardanoMetadataTxSubmitter} bean is registered at all. The context must
 * still start and {@code KeriAuthBeginService} must still exist as a bean, since it now depends on an
 * {@code ObjectProvider<CardanoMetadataTxSubmitter>} rather than the port directly.
 */
class KeriAuthBeginServiceMissingSubmitterContextTest {

    @Test
    void moduleEnabledWithoutACardanoMetadataTxSubmitterBeanStillConstructsKeriAuthBeginService() {
        new ApplicationContextRunner()
                .withPropertyValues("lob.keri-attestation.keria.url=https://example.org")
                .withBean(KeriAttestationClient.class, () -> mock(KeriAttestationClient.class))
                .withBean(KeriAttestationProperties.class,
                        KeriAuthBeginServiceMissingSubmitterContextTest::properties)
                .withBean(CesrChainReducer.class, () -> mock(CesrChainReducer.class))
                .withBean(CredentialCesrFetcher.class, () -> mock(CredentialCesrFetcher.class))
                .withBean(Cip170MetadataFactory.class, () -> mock(Cip170MetadataFactory.class))
                .withBean(CeremonyService.class, () -> mock(CeremonyService.class))
                .withBean(KeriIdentityLinkRepository.class, () -> mock(KeriIdentityLinkRepository.class))
                .withBean(CredentialChainValidator.class, () -> mock(CredentialChainValidator.class))
                // Deliberately no CardanoMetadataTxSubmitter bean: this is the module-without-publisher
                // deployment shape the ObjectProvider indirection exists for.
                .withBean(KeriAuthBeginService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KeriAuthBeginService.class);
                });
    }

    private static KeriAttestationProperties properties() {
        return new KeriAttestationProperties(
                true, null, "identifier", null, null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT1.5S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), null);
    }
}
