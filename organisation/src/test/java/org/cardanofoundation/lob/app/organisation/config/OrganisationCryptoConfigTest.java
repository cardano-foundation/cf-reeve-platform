package org.cardanofoundation.lob.app.organisation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.crypto.SecretDecryptor;
import org.cardanofoundation.lob.app.support.crypto.SecretEncryptor;

/**
 * Gating and fail-fast behaviour of the encrypt-only crypto config. The cross-module shapes
 * (publisher, merged) live in the netsuite module's NetsuiteCryptoConfigTest — only that module
 * sees both crypto configs.
 */
class OrganisationCryptoConfigTest {

    private static String publicKeyB64;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        publicKeyB64 = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(OrganisationCryptoConfig.class);
    }

    @Test
    void providesAnEncryptorAndNoDecryptorWhenTheModuleIsEnabled() {
        runner()
                .withPropertyValues(
                        "lob.organisation.enabled=true",
                        "lob.security.config-encryption.public-key=" + publicKeyB64)
                .run(context -> {
                    assertThat(context).hasSingleBean(SecretEncryptor.class);
                    assertThat(context).doesNotHaveBean(SecretDecryptor.class);
                });
    }

    @Test
    void staysInertWhenTheModuleIsDisabledEvenWithoutAKey() {
        // The publisher pod's situation: the organisation package is component-scanned there, but
        // the module is disabled and no public key is configured. Startup must succeed.
        runner()
                .withPropertyValues("lob.organisation.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecretEncryptor.class);
                });
    }

    @Test
    void failsStartupNamingTheKeyWhenTheKeyIsMissing() {
        runner()
                .withPropertyValues("lob.organisation.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public key");
                });
    }

    @Test
    void failsStartupNamingTheKeyWhenTheKeyIsGarbage() {
        runner()
                .withPropertyValues(
                        "lob.organisation.enabled=true",
                        "lob.security.config-encryption.public-key=not-a-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("public key");
                });
    }

}
