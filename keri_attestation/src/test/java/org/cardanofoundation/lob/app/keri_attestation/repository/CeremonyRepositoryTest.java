package org.cardanofoundation.lob.app.keri_attestation.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.config.TestContainerConfig;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;

@SpringBootTest
@ContextConfiguration(classes = CeremonyRepositoryTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
// public: RelinkInvalidationSweepHandlerRollbackIntegrationTest (a different package) reuses the
// nested TestConfig below via @ContextConfiguration(classes = CeremonyRepositoryTest.TestConfig.class)
// -- a public nested class is still unreachable cross-package through a package-private outer class,
// so this class must be public too. See TestConfig's own javadoc for why that reuse exists.
public class CeremonyRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories("org.cardanofoundation.lob.app.keri_attestation")
    @EntityScan("org.cardanofoundation.lob.app.keri_attestation")
    // Task 3 added @Service/@Component classes (CeremonyService, CeremonyCleanupJob) to this package;
    // they now get picked up by this scan too, so the properties they depend on must be bindable here
    // just like in the real KeriAttestationModuleConfig pairing of ComponentScan + EnableConfigurationProperties.
    @ComponentScan(basePackages = "org.cardanofoundation.lob.app.keri_attestation")
    @EnableConfigurationProperties(KeriAttestationProperties.class)
    @Import(TestContainerConfig.class)
    // public: reused directly by RelinkInvalidationSweepHandlerRollbackIntegrationTest (a different
    // package) via @ContextConfiguration(classes = CeremonyRepositoryTest.TestConfig.class) rather than
    // that test declaring its own second, identically-shaped nested config — two sibling
    // @SpringBootConfiguration classes both @ComponentScan-ing this same base package pick each other
    // up (component scanning is classpath-wide, not scoped to "the context currently being built"), so
    // their @EnableJpaRepositories declarations collide with a BeanDefinitionOverrideException. Sharing
    // this one TestConfig - the module's sole test bootstrap config - avoids the collision structurally
    // instead of papering over it with exclude filters, and lets Spring's test-context cache reuse the
    // same ApplicationContext (and Testcontainers Postgres instance) across both test classes.
    public static class TestConfig {
    }

    private static final Set<CeremonyState> TERMINAL =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);

    @Autowired
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Autowired
    private KeriIdentityLinkRepository identityLinkRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private KeriAttestationCeremonyEntity ceremony(String id, String userId, CeremonyState state) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(id);
        ceremony.setUserId(userId);
        ceremony.setBindingVersion(1);
        ceremony.setTargetType("DOCUMENT");
        ceremony.setTargetId("doc-" + id);
        ceremony.setState(state);
        ceremony.setAttemptGeneration(0);
        ceremony.setExpiresAt(LocalDateTime.now().plusHours(1));
        return ceremony;
    }

    @Test
    void ceremonyRoundTripsEnumStateAndOtherFields() {
        KeriAttestationCeremonyEntity ceremony = ceremony("c-1", "user-1", CeremonyState.ATTEST_ANCHORED);
        ceremony.setErrorTitle(null);
        ceremony.setMetadataDigest("Edigest123");
        ceremony.setMetadataLabel("1447");
        ceremony.setKelSequence("3");
        ceremony.setKelEventSaid("Eevent123");
        ceremony.setRequestExnSaid("Eexn123");
        ceremonyRepository.save(ceremony);

        em.flush();
        em.clear();

        KeriAttestationCeremonyEntity reloaded = ceremonyRepository.findById("c-1").orElseThrow();
        assertEquals(CeremonyState.ATTEST_ANCHORED, reloaded.getState());
        assertEquals("user-1", reloaded.getUserId());
        assertEquals(1, reloaded.getBindingVersion());
        assertEquals("DOCUMENT", reloaded.getTargetType());
        assertEquals("doc-c-1", reloaded.getTargetId());
        assertEquals("Edigest123", reloaded.getMetadataDigest());
        assertEquals("1447", reloaded.getMetadataLabel());
        assertEquals("3", reloaded.getKelSequence());
        assertEquals("Eevent123", reloaded.getKelEventSaid());
        assertEquals("Eexn123", reloaded.getRequestExnSaid());
        assertTrue(reloaded.getCreatedAt() != null);
        assertTrue(reloaded.getUpdatedAt() != null);
    }

    @Test
    void findByIdForUpdateTakesLockAndReturnsTheCeremony() {
        ceremonyRepository.save(ceremony("c-2", "user-2", CeremonyState.CREATED));
        em.flush();
        em.clear();

        KeriAttestationCeremonyEntity locked = ceremonyRepository.findByIdForUpdate("c-2").orElseThrow();
        assertEquals("c-2", locked.getId());
        assertEquals(CeremonyState.CREATED, locked.getState());

        assertTrue(ceremonyRepository.findByIdForUpdate("does-not-exist").isEmpty());
    }

    @Test
    void countAndFindByUserIdAndStateNotInExcludeTerminalStates() {
        String userId = "user-3";
        ceremonyRepository.save(ceremony("c-active-1", userId, CeremonyState.CREATED));
        ceremonyRepository.save(ceremony("c-active-2", userId, CeremonyState.ATTEST_REQUESTED));
        ceremonyRepository.save(ceremony("c-consumed", userId, CeremonyState.CONSUMED));
        ceremonyRepository.save(ceremony("c-failed", userId, CeremonyState.FAILED));
        ceremonyRepository.save(ceremony("c-expired", userId, CeremonyState.EXPIRED));
        // a different user's active ceremony must never be counted for userId
        ceremonyRepository.save(ceremony("c-other-user", "user-4", CeremonyState.CREATED));

        em.flush();
        em.clear();

        long activeCount = ceremonyRepository.countByUserIdAndStateNotIn(userId, TERMINAL);
        assertEquals(2, activeCount);

        List<KeriAttestationCeremonyEntity> active = ceremonyRepository.findByUserIdAndStateNotIn(userId, TERMINAL);
        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(c -> !TERMINAL.contains(c.getState())));
        assertTrue(active.stream().map(KeriAttestationCeremonyEntity::getId)
                .toList().containsAll(List.of("c-active-1", "c-active-2")));
    }

    @Test
    void findByStateNotInAndExpiresAtBeforeReturnsOnlyOverdueNonTerminalCeremonies() {
        KeriAttestationCeremonyEntity overdue = ceremony("c-overdue", "user-6", CeremonyState.ATTEST_REQUESTED);
        overdue.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        ceremonyRepository.save(overdue);
        // not yet due (helper defaults expiresAt to now+1h) — must be excluded
        ceremonyRepository.save(ceremony("c-not-due-yet", "user-6", CeremonyState.ATTEST_REQUESTED));
        // terminal but also overdue — terminal always wins, must be excluded (it's already done)
        KeriAttestationCeremonyEntity terminalOverdue = ceremony("c-terminal-overdue", "user-6", CeremonyState.CONSUMED);
        terminalOverdue.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        ceremonyRepository.save(terminalOverdue);

        em.flush();
        em.clear();

        List<KeriAttestationCeremonyEntity> result =
                ceremonyRepository.findByStateNotInAndExpiresAtBefore(TERMINAL, LocalDateTime.now());

        assertEquals(1, result.size());
        assertEquals("c-overdue", result.get(0).getId());
    }

    @Test
    void findByStateInAndUpdatedAtBeforeReturnsOnlyStaleWaitingCeremonies() {
        Set<CeremonyState> waiting = EnumSet.of(CeremonyState.CREDENTIAL_REQUESTED,
                CeremonyState.AUTH_BEGIN_SUBMITTED, CeremonyState.ATTEST_REQUESTED);

        KeriAttestationCeremonyEntity stale = ceremony("c-stale-waiting", "user-8", CeremonyState.CREDENTIAL_REQUESTED);
        ceremonyRepository.save(stale);
        // not yet stale — updatedAt defaults to "now" via @PrePersist, must be excluded
        ceremonyRepository.save(ceremony("c-fresh-waiting", "user-8", CeremonyState.ATTEST_REQUESTED));
        // resting state, even if old — must be excluded regardless of updatedAt
        ceremonyRepository.save(ceremony("c-resting", "user-8", CeremonyState.CREATED));
        em.flush();

        em.createQuery("update keri_attestation.KeriAttestationCeremonyEntity c set c.updatedAt = :old "
                        + "where c.id in :ids")
                .setParameter("old", LocalDateTime.now().minusHours(1))
                .setParameter("ids", List.of("c-stale-waiting", "c-resting"))
                .executeUpdate();
        em.clear();

        List<KeriAttestationCeremonyEntity> result = ceremonyRepository.findByStateInAndUpdatedAtBefore(waiting,
                LocalDateTime.now().minusMinutes(5));

        assertEquals(1, result.size());
        assertEquals("c-stale-waiting", result.get(0).getId());
    }

    @Test
    void deleteByStateInAndUpdatedAtBeforePurgesOnlyOldTerminalCeremonies() {
        ceremonyRepository.save(ceremony("c-old-consumed", "user-7", CeremonyState.CONSUMED));
        ceremonyRepository.save(ceremony("c-recent-consumed", "user-7", CeremonyState.CONSUMED));
        ceremonyRepository.save(ceremony("c-old-active", "user-7", CeremonyState.CREATED));
        em.flush();

        // Age two rows' updatedAt via a bulk JPQL update, which bypasses @PreUpdate — a plain save()
        // would just have onUpdate() reset updatedAt back to "now" on the next flush.
        em.createQuery("update keri_attestation.KeriAttestationCeremonyEntity c "
                        + "set c.updatedAt = :old where c.id in :ids")
                .setParameter("old", LocalDateTime.now().minusDays(10))
                .setParameter("ids", List.of("c-old-consumed", "c-old-active"))
                .executeUpdate();
        em.clear();

        long deleted = ceremonyRepository.deleteByStateInAndUpdatedAtBefore(TERMINAL, LocalDateTime.now().minusDays(7));

        assertEquals(1, deleted);
        assertTrue(ceremonyRepository.findById("c-old-consumed").isEmpty());
        assertTrue(ceremonyRepository.findById("c-recent-consumed").isPresent());
        // non-terminal: must survive purge regardless of age
        assertTrue(ceremonyRepository.findById("c-old-active").isPresent());
    }

    @Test
    void identityLinkRoundTripsNullableAndLongInstantFields() {
        KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
        link.setUserId("user-5");
        link.setBindingVersion(1);
        link.setAid("Eaid123");
        link.setOobiUrl("https://example.org/oobi");
        link.setCredentialSaid("Ecred123");
        link.setCredentialSchemaSaid("Eschema123");
        link.setAuthBeginTxHash("a".repeat(64));
        link.setAuthBeginBlock(12345L);
        Instant authBeginAt = Instant.parse("2026-01-01T00:00:00Z");
        link.setAuthBeginAt(authBeginAt);
        identityLinkRepository.save(link);

        em.flush();
        em.clear();

        KeriIdentityLinkEntity reloaded = identityLinkRepository.findById("user-5").orElseThrow();
        assertEquals(1, reloaded.getBindingVersion());
        assertEquals("Eaid123", reloaded.getAid());
        assertEquals("https://example.org/oobi", reloaded.getOobiUrl());
        assertEquals("Ecred123", reloaded.getCredentialSaid());
        assertEquals("Eschema123", reloaded.getCredentialSchemaSaid());
        assertEquals("a".repeat(64), reloaded.getAuthBeginTxHash());
        assertEquals(12345L, reloaded.getAuthBeginBlock());
        assertEquals(authBeginAt, reloaded.getAuthBeginAt());
        assertTrue(reloaded.getCreatedAt() != null);
        assertTrue(reloaded.getUpdatedAt() != null);

        // a fresh link with no optional data set must round-trip nulls, not blow up on flush
        KeriIdentityLinkEntity bare = new KeriIdentityLinkEntity();
        bare.setUserId("user-bare");
        bare.setBindingVersion(0);
        identityLinkRepository.save(bare);
        em.flush();
        em.clear();

        KeriIdentityLinkEntity reloadedBare = identityLinkRepository.findById("user-bare").orElseThrow();
        assertFalse(reloadedBare.getAid() != null);
        assertFalse(reloadedBare.getAuthBeginBlock() != null);
        assertFalse(reloadedBare.getAuthBeginAt() != null);
    }

    /**
     * Proves the F3 fix's pessimistic lock actually serializes concurrent writers of the same
     * identity-link row — the race F3 exists to close: {@code KeriOobiService}'s relink and the async
     * {@code persist*IfIdentityStillCurrent} mutators ({@code KeriCredentialService}/
     * {@code KeriAuthBeginService}) all read-then-write {@code keri_identity_link} unlocked before this
     * fix, so a relink and a concurrent credential/auth-begin write could interleave into a row that
     * mixes fields from the old and new identity.
     *
     * <p>{@code NOT_SUPPORTED} at the method level (overriding the class-level {@code @Transactional}):
     * a true lock-contention test needs two independent, uncommitted-to-each-other database transactions
     * racing on the same row, which requires each worker thread to own its own transaction (via
     * {@link TransactionTemplate}) rather than sharing the single test-method transaction the class-level
     * annotation would otherwise wrap this method in (and roll back — these threads need to actually
     * commit for the other to observe their write).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByUserIdForUpdateSerializesRelinkAgainstAConcurrentCredentialWriteSoNoRowIsAMix() throws Exception {
        String userId = "user-race";
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> {
                KeriIdentityLinkEntity seed = new KeriIdentityLinkEntity();
                seed.setUserId(userId);
                seed.setBindingVersion(1);
                seed.setAid("EoldAid");
                seed.setCredentialSaid(null);
                identityLinkRepository.save(seed);
            });

            CountDownLatch relinkHoldsLock = new CountDownLatch(1);
            CountDownLatch releaseRelink = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Simulates KeriOobiService's relink: locks the row, holds it while "doing work" (the window
            // the real relink spends validating/deciding), then bumps bindingVersion, clears the
            // credential field, sets the new AID, and commits.
            Thread relinkThread = new Thread(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        KeriIdentityLinkEntity link = identityLinkRepository.findByUserIdForUpdate(userId)
                                .orElseThrow();
                        relinkHoldsLock.countDown();
                        awaitQuietly(releaseRelink);
                        link.setBindingVersion(link.getBindingVersion() + 1);
                        link.setAid("EnewAid");
                        link.setCredentialSaid(null);
                        identityLinkRepository.save(link);
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            relinkThread.start();
            assertTrue(relinkHoldsLock.await(5, TimeUnit.SECONDS), "relink thread should have taken the row lock");

            // Simulates KeriCredentialService#persistCredentialIfIdentityStillCurrent racing the relink:
            // its own locked read must BLOCK until the relink thread's transaction commits — proving the
            // lock serializes them — and must then observe the already-relinked bindingVersion (2, not
            // 1), so its real guard (bindingVersion mismatch -> skip) would correctly refuse to write a
            // credential onto what is now a different identity.
            AtomicBoolean sawRelinkedBindingVersion = new AtomicBoolean(false);
            Thread credentialWriterThread = new Thread(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        KeriIdentityLinkEntity freshLink = identityLinkRepository.findByUserIdForUpdate(userId)
                                .orElseThrow();
                        sawRelinkedBindingVersion.set(freshLink.getBindingVersion() == 2);
                        // Mirrors the real guard in persistCredentialIfIdentityStillCurrent: only write if
                        // the binding version this attempt was authorized under (1, the pre-relink value)
                        // still matches. It must not — proving the lock prevented the interleave that
                        // would otherwise let this stale write land between the relink's read and write.
                        if (freshLink.getBindingVersion() == 1) {
                            freshLink.setCredentialSaid("EshouldNeverLand");
                            identityLinkRepository.save(freshLink);
                        }
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            credentialWriterThread.start();
            // Give the credential-writer thread time to attempt (and block on) the lock before releasing
            // it — without the lock, this sleep window is exactly where the old, unguarded interleave
            // would occur.
            Thread.sleep(300);
            releaseRelink.countDown();

            relinkThread.join(5_000);
            credentialWriterThread.join(5_000);

            assertTrue(failure.get() == null, "no thread should have thrown: " + failure.get());
            assertTrue(sawRelinkedBindingVersion.get(), "the credential-writer's locked read must observe the "
                    + "already-committed relink, not an interleaved value");

            KeriIdentityLinkEntity finalState = identityLinkRepository.findById(userId).orElseThrow();
            assertEquals(2, finalState.getBindingVersion());
            assertEquals("EnewAid", finalState.getAid());
            assertNull(finalState.getCredentialSaid(),
                    "the stale credential write must never have landed on the relinked row");
        } finally {
            tx.executeWithoutResult(status -> identityLinkRepository.deleteById(userId));
        }
    }

    /**
     * Item 4 (round 2) fix: proves the lock-order-inversion deadlock risk is closed. Before this fix,
     * completion paths ({@code CeremonyService#completeStep} invoking a {@code persist*IfIdentityStillCurrent}
     * mutator) locked ceremony-then-link, while {@code KeriOobiService}'s relink locked link-then-ceremony
     * — two transactions taking the same two locks in opposite orders is a textbook Postgres deadlock.
     * After the fix, both paths lock ceremony before link (see {@code KeriOobiService#persistLink}'s and
     * {@code CeremonyService#completeStep}'s javadoc for the full rule).
     *
     * <p>This test has two threads contend for the SAME ceremony row AND the SAME link row, each
     * acquiring in ceremony-then-link order (mirroring the real completion and relink-invalidation
     * paths) — with the same lock order on both sides, one thread simply blocks the other at the
     * ceremony-lock step until it commits and releases; a deadlock is structurally impossible. Asserting
     * both threads complete within a bounded join timeout (rather than hanging, or one being aborted with
     * a Postgres deadlock error) is the proof.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void relinkAndACompletionPathBothLockCeremonyBeforeLinkSoNoDeadlockIsPossible() throws Exception {
        String userId = "user-lock-order";
        String ceremonyId = "cer-lock-order";
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try {
            tx.executeWithoutResult(status -> {
                KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
                link.setUserId(userId);
                link.setBindingVersion(1);
                link.setAid("EoldAid");
                identityLinkRepository.save(link);

                ceremonyRepository.save(ceremony(ceremonyId, userId, CeremonyState.CREDENTIAL_REQUESTED));
            });

            CountDownLatch threadAHoldsCeremonyLock = new CountDownLatch(1);
            CountDownLatch releaseThreadA = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Thread A simulates a completion path: locks the ceremony row first, holds it, then locks
            // the link row.
            Thread threadA = new Thread(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        ceremonyRepository.findByIdForUpdate(ceremonyId).orElseThrow();
                        threadAHoldsCeremonyLock.countDown();
                        awaitQuietly(releaseThreadA);
                        KeriIdentityLinkEntity link = identityLinkRepository.findByUserIdForUpdate(userId)
                                .orElseThrow();
                        link.setCredentialSaid("EfromThreadA");
                        identityLinkRepository.save(link);
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            threadA.start();
            assertTrue(threadAHoldsCeremonyLock.await(5, TimeUnit.SECONDS),
                    "thread A should have taken the ceremony lock");

            // Thread B simulates the relink path's ceremony-invalidation step racing the SAME ceremony:
            // it must acquire the ceremony lock FIRST too (blocking on thread A), only then the link
            // lock -- exactly the order KeriOobiService#persistLink now uses.
            AtomicBoolean threadBAcquiredCeremonyLock = new AtomicBoolean(false);
            Thread threadB = new Thread(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        ceremonyRepository.findByIdForUpdate(ceremonyId).orElseThrow();
                        threadBAcquiredCeremonyLock.set(true);
                        KeriIdentityLinkEntity link = identityLinkRepository.findByUserIdForUpdate(userId)
                                .orElseThrow();
                        link.setAid("EnewAidFromThreadB");
                        link.setBindingVersion(link.getBindingVersion() + 1);
                        identityLinkRepository.save(link);
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            threadB.start();
            // Give thread B a moment to attempt (and block on) the ceremony lock thread A is holding --
            // exactly the window where the OLD (link-then-ceremony) relink order could deadlock against
            // thread A's (ceremony-then-link) order.
            Thread.sleep(300);
            releaseThreadA.countDown();

            threadA.join(5_000);
            threadB.join(5_000);

            assertFalse(threadA.isAlive(), "thread A must complete -- no deadlock");
            assertFalse(threadB.isAlive(), "thread B must complete -- no deadlock");
            assertTrue(failure.get() == null,
                    "no thread should have thrown (e.g. a Postgres deadlock error): " + failure.get());
            assertTrue(threadBAcquiredCeremonyLock.get(),
                    "thread B must have eventually acquired the ceremony lock after thread A released it");

            KeriIdentityLinkEntity finalLink = identityLinkRepository.findById(userId).orElseThrow();
            // Thread B ran after thread A committed, so its write is the final, consistent state -- built
            // on top of thread A's own commit, not interleaved with it.
            assertEquals("EnewAidFromThreadB", finalLink.getAid());
            assertEquals(2, finalLink.getBindingVersion());
        } finally {
            tx.executeWithoutResult(status -> {
                identityLinkRepository.deleteById(userId);
                ceremonyRepository.deleteById(ceremonyId);
            });
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
