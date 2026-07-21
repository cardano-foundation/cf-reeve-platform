package org.cardanofoundation.lob.app.keri_attestation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Dedicated {@code @Async} dispatch bean for the module's long-running wallet/chain waits (design
 * §4.2 "waiting steps run on a dedicated async executor"; brief Task 9). Self-invocation does not go
 * through Spring's AOP proxy, so {@code @Async} only takes effect on calls made from a
 * <em>different</em> bean — this class exists solely so {@link KeriAttestService#startAttest} and
 * {@link KeriAuthBeginService#submitAuthBegin} can dispatch their continuations as true external
 * calls instead of self-invoking an {@code @Async} method on themselves, which Spring would run
 * synchronously on the caller's own thread, silently defeating the entire point.
 *
 * <p>All three methods are thin delegates to the owning service's own public, directly
 * unit-testable method: {@link #awaitPresentation} to the already-built (Task 7)
 * {@link KeriCredentialService#awaitPresentation}; {@link #awaitAnchor} to
 * {@link KeriAttestService#awaitAnchor}; {@link #awaitAuthBeginConfirmation} to
 * {@link KeriAuthBeginService#awaitAuthBeginConfirmation}. The first two are pinned to
 * {@code keriAttestationExecutor}; {@link #awaitAuthBeginConfirmation} is pinned to a separate
 * {@code keriAttestationConfirmationExecutor} — see
 * {@link org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationAsyncConfig}'s javadoc
 * for why (F3 fix: a long AUTH_BEGIN confirmation poll must never starve short wallet-approval waits).
 *
 * <p><b>Circular dependency, and why it's resolved with {@code @Lazy}:</b> {@link KeriAttestService},
 * {@link KeriCredentialService} (Task 10's {@code startCredentialRequest}) and
 * {@link KeriAuthBeginService} all dispatch back through this same bean (to run their own continuation
 * asynchronously), which would otherwise be a genuine constructor-injection cycle (e.g.
 * {@code KeriAttestService -> CeremonyAsyncRunner -> KeriAttestService}). All three are injected here
 * as {@code @Lazy} constructor parameters to break it: Spring hands this bean a deferred-resolution
 * proxy for each instead of forcing their eager construction while this bean itself is still being
 * built, and the proxy resolves to the real (by then fully constructed) bean the first time an
 * {@code @Async} method here actually runs — always well after context startup has finished
 * constructing every singleton, so there is no risk of dereferencing a half-built bean.
 */
@Component
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class CeremonyAsyncRunner {

    private final KeriAttestService attestService;
    private final KeriCredentialService credentialService;
    private final KeriAuthBeginService authBeginService;

    public CeremonyAsyncRunner(@Lazy KeriAttestService attestService, @Lazy KeriCredentialService credentialService,
            @Lazy KeriAuthBeginService authBeginService) {
        this.attestService = attestService;
        this.credentialService = credentialService;
        this.authBeginService = authBeginService;
    }

    @Async("keriAttestationExecutor")
    public void awaitAnchor(String ceremonyId, int generation) {
        attestService.awaitAnchor(ceremonyId, generation);
    }

    @Async("keriAttestationExecutor")
    public void awaitPresentation(String ceremonyId, int generation) {
        credentialService.awaitPresentation(ceremonyId, generation);
    }

    // Dedicated confirmation pool (F3 fix): this poll loop blocks for up to
    // KeriAttestationProperties#authBeginRollbackWindow() (default 30 minutes), so it must never share
    // a pool with the short (<=remotesignTimeout, default 3 minutes) wallet-approval waits above —
    // see KeriAttestationAsyncConfig's javadoc.
    @Async("keriAttestationConfirmationExecutor")
    public void awaitAuthBeginConfirmation(String ceremonyId, int generation) {
        authBeginService.awaitAuthBeginConfirmation(ceremonyId, generation);
    }
}
