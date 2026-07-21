package org.cardanofoundation.lob.app.keri_attestation.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
import org.springframework.transaction.annotation.Transactional;

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
class CeremonyRepositoryTest {

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
    static class TestConfig {
    }

    private static final Set<CeremonyState> TERMINAL =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);

    @Autowired
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Autowired
    private KeriIdentityLinkRepository identityLinkRepository;
    @Autowired
    private EntityManager em;

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
}
