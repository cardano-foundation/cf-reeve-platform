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
     * Unpaged: the addressbook (recipients) is paged in memory via {@code PagedResponse.ofList}.
     * One row per key per org, so the whole org directory is cheap to load.
     */
    List<VaultKeyEntity> findByOrganisationId(String organisationId);

    /** Paged variant for the org key-management listing. */
    Page<VaultKeyEntity> findByOrganisationId(String organisationId, Pageable pageable);

    List<VaultKeyEntity> findByAccountIdInAndOrganisationId(Collection<String> accountIds, String organisationId);
}
