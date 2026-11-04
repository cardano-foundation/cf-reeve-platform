package org.cardanofoundation.lob.app.keri_attestation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.keri_attestation.domain.entity.CredentialVerificationEntity;

@Repository
public interface CredentialVerificationRepository extends JpaRepository<CredentialVerificationEntity, String> {

    Optional<CredentialVerificationEntity> findByOrganisationIdAndPublicKey(String organisationId, String publicKey);

    /** Bulk lookup, so rendering a list of keys or contacts is one query rather than one per row. */
    List<CredentialVerificationEntity> findByOrganisationIdAndPublicKeyIn(String organisationId,
            Collection<String> publicKeys);
}
