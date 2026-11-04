package org.cardanofoundation.lob.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.organisation.config.OrganisationCryptoConfig;
import org.cardanofoundation.lob.app.support.crypto.SecretDecryptor;
import org.cardanofoundation.lob.app.support.crypto.SecretEncryptor;

/**
 * Cross-module wiring shapes, using the REAL crypto configs of both modules — this is the only
 * module whose test classpath sees them both. Registering the support-module config directly
 * would not catch a dropped {@code @Import}, so these tests never do that.
 */
class NetsuiteCryptoConfigTest {

    private static String publicA;
    private static String privateA;
    private static String privateB;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pairA = generator.generateKeyPair();
        KeyPair pairB = generator.generateKeyPair();
        publicA = Base64.getEncoder().encodeToString(pairA.getPublic().getEncoded());
        privateA = Base64.getEncoder().encodeToString(pairA.getPrivate().getEncoded());
        privateB = Base64.getEncoder().encodeToString(pairB.getPrivate().getEncoded());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OrganisationCryptoConfig.class, NetsuiteCryptoConfig.class);
    }

    /**
     * The publisher pod: the organisation package is scanned (so its crypto config is registered)
     * but the module is disabled, the netsuite module is enabled, and only the private key is
     * configured. This exact shape bricked the pod in an earlier design draft.
     */
    @Test
    void publisherShapeStartsWithOnlyThePrivateKey() {
        runner()
                .withPropertyValues(
                        "lob.organisation.enabled=false",
                        "lob.netsuite.enabled=true",
                        "lob.security.config-encryption.private-key=" + privateA)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecretDecryptor.class);
                    assertThat(context).doesNotHaveBean(SecretEncryptor.class);
                });
    }

    @Test
    void mergedShapeStartsWhenTheKeysAreAPair() {
        runner()
                .withPropertyValues(
                        "lob.organisation.enabled=true",
                        "lob.netsuite.enabled=true",
                        "lob.security.config-encryption.public-key=" + publicA,
                        "lob.security.config-encryption.private-key=" + privateA)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecretEncryptor.class);
                    assertThat(context).hasSingleBean(SecretDecryptor.class);
                });
    }

    /**
     * Also proves the pair-check is actually registered through the crypto configs'
     * {@code @Import}: if both imports were dropped, no canary would run and this context would
     * start. (Either import alone suffices — the check only matters where both configs are
     * active, as here.)
     */
    @Test
    void mergedShapeFailsStartupWhenTheKeysAreNotAPair() {
        runner()
                .withPropertyValues(
                        "lob.organisation.enabled=true",
                        "lob.netsuite.enabled=true",
                        "lob.security.config-encryption.public-key=" + publicA,
                        "lob.security.config-encryption.private-key=" + privateB)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("key pair check failed")
                            .hasStackTraceContaining("key-pair mismatch");
                });
    }

    @Test
    void failsStartupNamingTheKeyWhenThePrivateKeyIsMissing() {
        runner()
                .withPropertyValues("lob.netsuite.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("private key");
                });
    }

    @Test
    void failsStartupNamingTheKeyWhenThePrivateKeyIsGarbage() {
        runner()
                .withPropertyValues(
                        "lob.netsuite.enabled=true",
                        "lob.security.config-encryption.private-key=not-a-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("private key");
                });
    }

    @Test
    void staysInertWhenTheNetsuiteModuleIsDisabled() {
        runner()
                .withPropertyValues(
                        "lob.organisation.enabled=false",
                        "lob.netsuite.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecretDecryptor.class);
                });
    }

}
