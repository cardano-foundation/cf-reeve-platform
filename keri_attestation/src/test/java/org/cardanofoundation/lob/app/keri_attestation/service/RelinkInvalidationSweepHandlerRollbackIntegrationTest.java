package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.CeremonyRepositoryTest;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;

/**
 * Fast-follow (whole-branch review, FF2): a real-Spring-context proof that {@link
 * RelinkInvalidationSweepHandler#onRelinkCompleted}'s {@code @TransactionalEventListener(AFTER_COMMIT,
 * fallbackExecution = true)} + {@code @Async} + {@code @Transactional(REQUIRES_NEW)} wiring actually
 * behaves as documented — mirroring document_vault's {@code
 * DocumentLedgerUpdateHandlerRollbackIntegrationTest} (the "real rollback test" for that module's twin
 * AFTER_COMMIT handoff, referenced from {@code DocumentLedgerUpdateHandlerListenerPinTest}'s javadoc).
 * {@link RelinkInvalidationSweepHandlerTest} is the reflection/unit-level analogue of that class's own
 * {@code DocumentLedgerUpdateHandlerListenerPinTest} — it calls {@code onRelinkCompleted} directly and so
 * proves the sweep LOGIC but can never observe whether Spring actually honors the annotations. This class
 * closes that gap the same way the document_vault precedent does: a real transactional publisher, a real
 * listener bean, real commit/rollback.
 *
 * <p>Deliberately does NOT enable {@code @EnableAsync} here, for the same reason the document_vault
 * precedent doesn't: the safety property under test — no ceremony is failed until the relink transaction
 * that published {@link RelinkCompletedEvent} has actually committed — is guaranteed purely by {@code
 * @Transactional(propagation = REQUIRES_NEW)}, which always suspends the caller's transaction and opens a
 * brand-new, independent one on its own connection regardless of which thread runs it, so the property
 * holds whether or not {@code @Async} happens to be genuinely asynchronous in this context. Assertions
 * still poll with Awaitility rather than asserting immediately, purely as a defensive measure in case
 * {@code @Async} ever does become real here.
 *
 * <p>Reuses {@link CeremonyRepositoryTest.TestConfig} rather than declaring a second, identically-shaped
 * {@code @SpringBootConfiguration} of its own: two sibling test configs both {@code @ComponentScan}-ing
 * the same {@code org.cardanofoundation.lob.app.keri_attestation} base package pick each other up
 * (classpath component scanning is not scoped to "the context currently being built"), and each one's
 * own {@code @EnableJpaRepositories} for the same repository interfaces collides with a {@code
 * BeanDefinitionOverrideException} — see that class's {@code TestConfig} javadoc for the full
 * explanation. Sharing the one config also lets Spring's test-context cache reuse the same
 * {@code ApplicationContext} (and Testcontainers Postgres instance) across both test classes.
 */
@SpringBootTest
@ContextConfiguration(classes = CeremonyRepositoryTest.TestConfig.class)
@ActiveProfiles("test")
class RelinkInvalidationSweepHandlerRollbackIntegrationTest {

    private static final String USER = "user-relink-wiring";

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private KeriAttestationCeremonyRepository ceremonyRepository;

    private KeriAttestationCeremonyEntity persistStaleBoundCeremony(String id) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(id);
        ceremony.setUserId(USER);
        ceremony.setBindingVersion(1);
        ceremony.setTargetType("DOCUMENT");
        ceremony.setTargetId("doc-" + id);
        ceremony.setState(CeremonyState.CREDENTIAL_REQUESTED);
        ceremony.setAttemptGeneration(0);
        ceremony.setExpiresAt(LocalDateTime.now().plusHours(1));
        return ceremonyRepository.saveAndFlush(ceremony);
    }

    @Test
    void staleBoundCeremonyIsFailedAfterTheRelinkTransactionCommits() {
        KeriAttestationCeremonyEntity ceremony = persistStaleBoundCeremony("cer-commit");

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> eventPublisher.publishEvent(new RelinkCompletedEvent(USER, 2)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            KeriAttestationCeremonyEntity reloaded = ceremonyRepository.findById(ceremony.getId()).orElseThrow();
            assertEquals(CeremonyState.FAILED, reloaded.getState());
            assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, reloaded.getErrorTitle());
        });
    }

    @Test
    void sweepDoesNotFireWhenTheRelinkTransactionRollsBack() {
        KeriAttestationCeremonyEntity ceremony = persistStaleBoundCeremony("cer-rollback");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new RelinkCompletedEvent(USER, 2));
            status.setRollbackOnly();
        });

        // AFTER_COMMIT never fires for a rolled-back transaction. Note the fallbackExecution nuance:
        // fallbackExecution = true only changes behavior when NO transaction synchronization is present
        // AT ALL at publish time (see the fallback test below) — here a real synchronization WAS present,
        // it simply never reached commit, so fallbackExecution does not rescue this publish. There is
        // nothing to genuinely await, so poll for a short window (long enough for a background executor
        // to have run, had the listener incorrectly fired) and assert the ceremony stayed exactly as it
        // was.
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    KeriAttestationCeremonyEntity reloaded =
                            ceremonyRepository.findById(ceremony.getId()).orElseThrow();
                    assertNotEquals(CeremonyState.FAILED, reloaded.getState());
                    assertEquals(CeremonyState.CREDENTIAL_REQUESTED, reloaded.getState());
                });
    }

    @Test
    void sweepFiresViaFallbackExecutionWhenPublishedOutsideAnyTransaction() {
        KeriAttestationCeremonyEntity ceremony = persistStaleBoundCeremony("cer-fallback");

        // No TransactionTemplate here at all: no active transaction synchronization is present, which is
        // exactly the case fallbackExecution = true exists for (e.g. a caller re-publishing after its own
        // transaction has already completed). Contrast with the rollback case above, where a
        // synchronization WAS present and fallbackExecution correctly does NOT apply.
        eventPublisher.publishEvent(new RelinkCompletedEvent(USER, 2));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            KeriAttestationCeremonyEntity reloaded = ceremonyRepository.findById(ceremony.getId()).orElseThrow();
            assertEquals(CeremonyState.FAILED, reloaded.getState());
            assertEquals(KeriAttestationProblems.IDENTITY_RELINKED, reloaded.getErrorTitle());
        });
    }
}
