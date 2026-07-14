package org.cardanofoundation.lob.app.document_vault.repository;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

public interface VaultDocumentRepository extends JpaRepository<VaultDocumentEntity, String> {

    /**
     * Org-wide listing with optional filters (all nullable) — direction is a String ('SENT'/'RECEIVED')
     * to keep the null-check portable; status is typed. Sorting/paging via Pageable.
     */
    String SEARCH_WHERE = "where d.organisationId = :organisationId "
            + "and (:status is null or d.status = :status) "
            + "and (:q is null or lower(d.fileName) like lower(concat('%', cast(:q as string), '%')) "
            + "     or lower(d.description) like lower(concat('%', cast(:q as string), '%'))) "
            + "and (:direction is null "
            + "     or (:direction = 'SENT' and d.createdByAccount = :accountId) "
            + "     or (:direction = 'RECEIVED' and exists ("
            + "         select 1 from document_vault.VaultKeyEntity k, in(d.slots) s "
            + "         where s.keyId = k.id and k.accountId = :accountId)))";

    // explicit countQuery: don't rely on Spring Data deriving a count over the exists-subquery form
    @Query(value = "select d from document_vault.VaultDocumentEntity d " + SEARCH_WHERE,
           countQuery = "select count(d) from document_vault.VaultDocumentEntity d " + SEARCH_WHERE)
    Page<VaultDocumentEntity> search(@Param("organisationId") String organisationId,
                                     @Param("accountId") String accountId,
                                     @Param("direction") String direction,
                                     @Param("status") VaultDocumentStatus status,
                                     @Param("q") String q,
                                     Pageable pageable);
}
