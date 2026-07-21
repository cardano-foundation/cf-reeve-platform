package org.cardanofoundation.lob.app.keri_attestation.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The dedicated async executor for this module's long-running wallet/chain waits (design §4.2
 * "waiting steps run on a dedicated async executor"). Named {@code keriAttestationExecutor} so it
 * cannot collide, by bean name, with any executor a host application already defines; every
 * {@code @Async("keriAttestationExecutor")} method on
 * {@link org.cardanofoundation.lob.app.keri_attestation.service.CeremonyAsyncRunner} is pinned to it
 * explicitly rather than falling back to Spring's shared default executor (or, worse, running on the
 * calling thread if no async executor were configured at all).
 *
 * <p>Two threads: this module dispatches at most three concurrent long-running waits per ceremony
 * (credential presentation, ATTEST anchor, AUTH_BEGIN confirmation) and each user is capped at
 * {@code limits.max-active-ceremonies-per-user} (default 3) open ceremonies, so a small fixed pool is
 * enough to avoid unbounded thread growth under the module's own per-user rate limits while still
 * allowing real concurrency across ceremonies/users. Not {@code @ConditionalOnProperty}-gated on its
 * own: harmless (an idle thread pool) if the module is enabled but no consumer ever schedules work on
 * it, and this bean itself never touches a KERI agent.
 */
@Configuration
public class KeriAttestationAsyncConfig {

    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 50;

    @Bean(name = "keriAttestationExecutor")
    public Executor keriAttestationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("keri-attest-");
        executor.initialize();
        return executor;
    }
}
