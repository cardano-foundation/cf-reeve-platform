package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;

public interface ReportTypeFieldRepository extends JpaRepository<ReportTypeFieldEntity, Long> {

    Optional<ReportTypeFieldEntity> findByReportIdAndId(Long reportId, Long id);

}
