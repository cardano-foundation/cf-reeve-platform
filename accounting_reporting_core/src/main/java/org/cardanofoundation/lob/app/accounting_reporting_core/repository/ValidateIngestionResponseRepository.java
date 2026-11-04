package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.ValidateIngestionResponseEntity;

public interface ValidateIngestionResponseRepository extends JpaRepository<ValidateIngestionResponseEntity, String> {

    @Transactional
    @Modifying
    @Query("DELETE FROM accounting_reporting_core.ValidateIngestionResponseEntity r WHERE r.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

}
