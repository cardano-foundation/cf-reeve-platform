package org.cardanofoundation.lob.app.keri_attestation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;

public interface KeriIdentityLinkRepository extends JpaRepository<KeriIdentityLinkEntity, String> {
}
