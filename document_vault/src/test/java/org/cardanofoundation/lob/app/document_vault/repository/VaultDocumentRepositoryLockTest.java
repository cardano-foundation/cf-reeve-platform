package org.cardanofoundation.lob.app.document_vault.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;

import org.junit.jupiter.api.Test;

/**
 * Why reflection and not a live two-thread race: a true concurrency test for the publish
 * double-fire race is out of proportion here, and would be flaky/slow. What CAN regress silently
 * is someone "simplifying" {@link VaultDocumentRepository#findByIdForUpdate} back to a plain
 * derived finder, which would quietly drop the {@code SELECT ... FOR UPDATE} that makes
 * {@code VaultDocumentService#publish} race-free. This pins the annotation the same way
 * {@code VaultDocumentControllerSecurityTest} pins the {@code @PreAuthorize} gate.
 */
class VaultDocumentRepositoryLockTest {

    @Test
    void findByIdForUpdateTakesAPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = VaultDocumentRepository.class
                .getMethod("findByIdForUpdate", String.class)
                .getAnnotation(Lock.class);

        assertNotNull(lock, "publish's finder must hold a row lock: two concurrent publish calls "
                + "must not both observe DRAFT and both fire the irreversible DocumentPublishCommand");
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }
}
