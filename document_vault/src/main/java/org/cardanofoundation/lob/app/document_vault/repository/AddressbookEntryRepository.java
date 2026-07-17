package org.cardanofoundation.lob.app.document_vault.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;

public interface AddressbookEntryRepository extends JpaRepository<AddressbookEntryEntity, String> {

    Page<AddressbookEntryEntity> findByOrganisationId(String organisationId, Pageable pageable);

    /** Unpaged, for the recipient picker: one row per contact per org, so the directory is cheap to load. */
    List<AddressbookEntryEntity> findByOrganisationId(String organisationId);

    /** The idempotency key for card re-import — mirrors UNIQUE (organisation_id, public_key). */
    Optional<AddressbookEntryEntity> findByOrganisationIdAndPublicKey(String organisationId, String publicKey);

    List<AddressbookEntryEntity> findByIdInAndOrganisationId(Collection<String> ids, String organisationId);
}
