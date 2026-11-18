package org.cardanofoundation.lob.app.document_vault.repository;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;

public interface WrappedRecordRepository extends JpaRepository<WrappedRecordEntity, WrappedRecordId> {

    Page<WrappedRecordEntity> findByIdAccountId(String accountId, Pageable pageable);
}
