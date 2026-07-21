package org.cardanofoundation.lob.app.keri_attestation.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The module's two dedicated async executors for its long-running wallet/chain waits (design §4.2
 * "waiting steps run on a dedicated async executor"; hardened by the F3 fix). Every
 * {@code @Async(...)} method on
 * {@link org.cardanofoundation.lob.app.keri_attestation.service.CeremonyAsyncRunner} is pinned
 * explicitly to one of these two beans rather than falling back to Spring's shared default executor
 * (or, worse, running on the calling thread if no async executor were configured at all).
 *
 * <p><b>Two pools, not one (F3):</b> {@code keriAttestationExecutor} carries the short wallet-approval
 * waits — credential presentation ({@code awaitPresentation}) and ATTEST anchoring
 * ({@code awaitAnchor}) — each bounded by {@link KeriAttestationProperties#remotesignTimeout()}
 * (default 3 minutes). {@code keriAttestationConfirmationExecutor} carries only
 * {@code awaitAuthBeginConfirmation}, a blocking poll loop bounded by the much longer
 * {@link KeriAttestationProperties#authBeginRollbackWindow()} (default 30 minutes). A single shared
 * pool previously let a burst of AUTH_BEGIN confirmations occupy every thread for up to half an hour,
 * starving every other ceremony's short wallet-approval wait behind them; splitting the pools makes
 * that starvation impossible by construction. Pool sizes are configurable via
 * {@link KeriAttestationProperties.Executor} (defaults: 4 wallet threads, 2 confirmation threads).
 *
 * <p>Not {@code @ConditionalOnProperty}-gated on their own: harmless (idle thread pools) if the module
 * is enabled but no consumer ever schedules work on them, and these beans never themselves touch a
 * KERI agent.
 */
@Configuration
public class KeriAttestationAsyncConfig {

    private static final int QUEUE_CAPACITY = 50;

    @Bean(name = "keriAttestationExecutor")
    public Executor keriAttestationExecutor(KeriAttestationProperties properties) {
        return buildExecutor(properties.executor().walletPoolSize(), "keri-attest-");
    }

    @Bean(name = "keriAttestationConfirmationExecutor")
    public Executor keriAttestationConfirmationExecutor(KeriAttestationProperties properties) {
        return buildExecutor(properties.executor().confirmationPoolSize(), "keri-confirm-");
    }

    private static Executor buildExecutor(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.initialize();
        return executor;
    }
}
