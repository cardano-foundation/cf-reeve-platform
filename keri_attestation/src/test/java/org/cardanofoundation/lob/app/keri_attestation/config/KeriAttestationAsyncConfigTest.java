package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.service.CeremonyAsyncRunner;

/**
 * F3 fix regression coverage (executor starvation): the wallet-wait executor (credential
 * presentation, ATTEST anchor — bounded by {@code remotesign-timeout}, default 3 minutes) and the
 * AUTH_BEGIN confirmation executor (bounded by {@code auth-begin-rollback-window}, up to 30 minutes
 * of blocking poll) must be two separate, independently sized thread pools — a single shared pool let
 * a burst of AUTH_BEGIN confirmations starve every wallet-wait ceremony for up to half an hour.
 */
class KeriAttestationAsyncConfigTest {

    private final KeriAttestationAsyncConfig config = new KeriAttestationAsyncConfig();

    @Test
    void walletExecutorUsesTheConfiguredPoolSize() {
        Executor executor = config.keriAttestationExecutor(properties(7, 2));

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) executor;
        assertThat(tpte.getCorePoolSize()).isEqualTo(7);
        assertThat(tpte.getMaxPoolSize()).isEqualTo(7);
    }

    @Test
    void confirmationExecutorUsesTheConfiguredPoolSizeAndIsDistinctFromTheWalletExecutor() {
        Executor wallet = config.keriAttestationExecutor(properties(4, 9));
        Executor confirmation = config.keriAttestationConfirmationExecutor(properties(4, 9));

        assertThat(confirmation).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor tpte = (ThreadPoolTaskExecutor) confirmation;
        assertThat(tpte.getCorePoolSize()).isEqualTo(9);
        assertThat(tpte.getMaxPoolSize()).isEqualTo(9);
        assertThat(confirmation).isNotSameAs(wallet);
    }

    @Test
    void bothExecutorsFallBackToTheDocumentedDefaultsWhenTheExecutorSectionIsAbsent() {
        KeriAttestationProperties withoutExecutor = new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT1.5S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), null);

        Executor wallet = config.keriAttestationExecutor(withoutExecutor);
        Executor confirmation = config.keriAttestationConfirmationExecutor(withoutExecutor);

        assertThat(((ThreadPoolTaskExecutor) wallet).getCorePoolSize()).isEqualTo(4);
        assertThat(((ThreadPoolTaskExecutor) confirmation).getCorePoolSize()).isEqualTo(2);
    }

    @Test
    void awaitAuthBeginConfirmationIsAnnotatedWithTheDedicatedConfirmationExecutor() throws NoSuchMethodException {
        Method method = CeremonyAsyncRunner.class.getMethod("awaitAuthBeginConfirmation", String.class, int.class);

        Async async = method.getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("keriAttestationConfirmationExecutor");
    }

    @Test
    void awaitPresentationAndAwaitAnchorRemainOnTheWalletExecutor() throws NoSuchMethodException {
        Method awaitPresentation = CeremonyAsyncRunner.class.getMethod("awaitPresentation", String.class, int.class);
        Method awaitAnchor = CeremonyAsyncRunner.class.getMethod("awaitAnchor", String.class, int.class);

        assertThat(awaitPresentation.getAnnotation(Async.class).value()).isEqualTo("keriAttestationExecutor");
        assertThat(awaitAnchor.getAnnotation(Async.class).value()).isEqualTo("keriAttestationExecutor");
    }

    private static KeriAttestationProperties properties(int walletPoolSize, int confirmationPoolSize) {
        return new KeriAttestationProperties(
                true, null, "identifier", null,
                Duration.parse("PT1H"), Duration.parse("PT24H"), Duration.parse("PT3M"), Duration.parse("PT1.5S"),
                3, new KeriAttestationProperties.Limits(3, Duration.parse("PT10S")),
                Duration.parse("PT15S"), Duration.parse("PT30M"), Duration.parse("PT2S"), Duration.parse("PT3S"),
                Duration.parse("PT2M"), new KeriAttestationProperties.Executor(walletPoolSize, confirmationPoolSize));
    }
}
