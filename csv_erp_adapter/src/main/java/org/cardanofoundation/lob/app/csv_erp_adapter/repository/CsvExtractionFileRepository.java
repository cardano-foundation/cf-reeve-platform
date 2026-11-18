package org.cardanofoundation.lob.app.csv_erp_adapter.repository;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.csv_erp_adapter.domain.entity.CsvExtractionFileEntity;

public interface CsvExtractionFileRepository extends JpaRepository<CsvExtractionFileEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM csv_erp_adapter.CsvExtractionFileEntity f WHERE f.id = :id")
    Optional<CsvExtractionFileEntity> findForUpdateById(@Param("id") String id);

    @Modifying
    @Query("DELETE FROM csv_erp_adapter.CsvExtractionFileEntity f WHERE f.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

}
