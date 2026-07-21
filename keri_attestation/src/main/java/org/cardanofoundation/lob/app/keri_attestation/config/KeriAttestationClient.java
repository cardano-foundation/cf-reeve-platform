package org.cardanofoundation.lob.app.keri_attestation.config;

import org.cardanofoundation.signify.app.clienting.SignifyClient;

/**
 * Holder for this module's own KERIA {@link SignifyClient} (F1 fix).
 *
 * <p>Legacy {@code blockchain_publisher} (its {@code KeriConfig}/{@code KeriService}/
 * {@code PublisherHealth}) injects an <em>unqualified</em> {@code SignifyClient} bean. If this module
 * also exposed a {@code SignifyClient} bean directly, an application context wiring both modules
 * together (as {@code blockchain_publisher} now depends on this module) would fail at startup with
 * {@code NoUniqueBeanDefinitionException} — Spring cannot pick between the two unqualified
 * {@code SignifyClient} beans, and this module may not touch the legacy files to add a qualifier
 * there.
 *
 * <p>The fix: this module never exposes a {@code SignifyClient} bean at all. {@link SignifyClientConfig}
 * constructs and connects the client (same connect→boot-fallback logic as before) and wraps it in this
 * final holder class, which is the <em>only</em> bean this module contributes for KERIA access. Every
 * consumer in this module ({@code KeriAgentService}, {@code KeriOobiService},
 * {@code KeriCredentialService}, {@code KeriAttestService}, {@code KeriAuthBeginService},
 * {@code KeriNotificationCorrelator}) injects this type and calls {@link #client()} to reach the
 * underlying {@link SignifyClient} — there is no by-type ambiguity for Spring to resolve, and no
 * {@code @Qualifier} plumbing is needed anywhere.
 */
public final class KeriAttestationClient {

    private final SignifyClient client;

    public KeriAttestationClient(SignifyClient client) {
        this.client = client;
    }

    public SignifyClient client() {
        return client;
    }
}
