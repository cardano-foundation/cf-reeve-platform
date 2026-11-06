package org.cardanofoundation.lob.app.document_vault.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentAttestationFreezeEntity;

public interface DocumentAttestationFreezeRepository extends JpaRepository<DocumentAttestationFreezeEntity, String> {

    /** {@code DocumentAttestationTargetProvider#prepareDigest}'s idempotency check: an existing row for
     *  this exact pair means the commitment was already frozen, so return its digest unchanged. */
    Optional<DocumentAttestationFreezeEntity> findByDocumentIdAndCeremonyId(String documentId, String ceremonyId);

    /** Publish-time lookup by the ceremony a document's publish request names. */
    List<DocumentAttestationFreezeEntity> findByCeremonyId(String ceremonyId);

    /** The cleanup job's discovery read: candidates older than the retention window, further filtered
     *  against ceremony state before deletion. */
    List<DocumentAttestationFreezeEntity> findByCreatedAtBefore(LocalDateTime cutoff);
}
