package org.cardanofoundation.lob.app.document_vault.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;

public interface VaultKeyRepository extends JpaRepository<VaultKeyEntity, String> {

    Page<VaultKeyEntity> findByAccountId(String accountId, Pageable pageable);

    Optional<VaultKeyEntity> findByIdAndAccountId(String id, String accountId);

    boolean existsByAccountIdAndOrganisationIdAndPublicKey(String accountId, String organisationId, String publicKey);

    Optional<VaultKeyEntity> findByAccountIdAndOrganisationIdAndPublicKey(String accountId, String organisationId, String publicKey);

    /**
     * Unpaged on purpose: the addressbook must drop keys whose issuer has been de-trusted
     * (contract §2.8.5) BEFORE paging, or pages would come back short. One row per key per org.
     */
    List<VaultKeyEntity> findByOrganisationId(String organisationId);

    List<VaultKeyEntity> findByAccountIdInAndOrganisationId(Collection<String> accountIds, String organisationId);
}
