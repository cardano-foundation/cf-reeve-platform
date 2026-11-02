package org.cardanofoundation.lob.app.support.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the pair-check LOGIC only — {@link SecretCipherConfig} is registered directly here.
 * That the config is actually imported into real module contexts is proven by the merged-shape
 * test in the netsuite module (NetsuiteCryptoConfigTest), which must fail startup on a
 * mismatched pair using the real crypto configs.
 */
class SecretCipherPairCheckTest {

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
        return new ApplicationContextRunner().withUserConfiguration(SecretCipherConfig.class);
    }

    @Test
    void startsWhenTheConfiguredKeysAreAPair() {
        runner()
                .withBean(SecretEncryptor.class, () -> new RsaOaepHybridEncryptor(publicA))
                .withBean(SecretDecryptor.class, () -> new RsaOaepHybridDecryptor(privateA))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupWhenTheKeysAreNotAPair() {
        runner()
                .withBean(SecretEncryptor.class, () -> new RsaOaepHybridEncryptor(publicA))
                .withBean(SecretDecryptor.class, () -> new RsaOaepHybridDecryptor(privateB))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("key pair check failed")
                            .hasStackTraceContaining("key-pair mismatch");
                });
    }

    @Test
    void reportsTheRealCauseInsteadOfBlamingTheKeyPairForUnrelatedFailures() {
        SecretEncryptor broken = (plaintext, organisationId) -> {
            throw new IllegalStateException("Failed to encrypt secret value: provider unavailable");
        };

        runner()
                .withBean(SecretEncryptor.class, () -> broken)
                .withBean(SecretDecryptor.class, () -> new RsaOaepHybridDecryptor(privateA))
                .run(context -> {
                    assertThat(context).hasFailed();
                    String trace = stackTrace(context.getStartupFailure());
                    assertThat(trace)
                            .contains("Failed to encrypt secret value: provider unavailable")
                            .doesNotContain("not a pair")
                            .doesNotContain("key-pair mismatch");
                });
    }

    @Test
    void failsStartupWhenTheCanaryComesBackAltered() {
        SecretDecryptor lying = (envelope, organisationId) -> "something else";

        runner()
                .withBean(SecretEncryptor.class, () -> new RsaOaepHybridEncryptor(publicA))
                .withBean(SecretDecryptor.class, () -> lying)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("did not survive the roundtrip");
                });
    }

    private static String stackTrace(Throwable throwable) {
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    @Test
    void noOpsWhenOnlyAnEncryptorIsConfigured() {
        runner()
                .withBean(SecretEncryptor.class, () -> new RsaOaepHybridEncryptor(publicA))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void noOpsWhenOnlyADecryptorIsConfigured() {
        runner()
                .withBean(SecretDecryptor.class, () -> new RsaOaepHybridDecryptor(privateA))
                .run(context -> assertThat(context).hasNotFailed());
    }

}
