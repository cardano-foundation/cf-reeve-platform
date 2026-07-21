package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;

public interface DocumentAttestationFreezeRepository extends JpaRepository<DocumentAttestationFreezeEntity, String> {

    /** {@code DocumentAttestationTargetProvider#prepareDigest}'s idempotency check (Task 13, design
     *  §5.2): an existing row for this exact pair means the digest was already frozen. */
    Optional<DocumentAttestationFreezeEntity> findByDocumentIdAndCeremonyId(String documentId, String ceremonyId);

    List<DocumentAttestationFreezeEntity> findByCeremonyId(String ceremonyId);

    /** {@code DocumentAttestationFreezeCleanupJob}'s discovery read (Task 13): candidates older than
     *  the retention window, further filtered against ceremony state before deletion. */
    List<DocumentAttestationFreezeEntity> findByCreatedAtBefore(LocalDateTime cutoff);

}
