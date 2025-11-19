package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;

@Repository
public interface ReportFieldRepository extends JpaRepository<ReportFieldEntity, Long> {

    List<ReportFieldEntity> findByReportId(String reportId);

    List<ReportFieldEntity> findByReportIdAndParentFieldIsNull(String reportId);

    List<ReportFieldEntity> findByFieldTemplateId(Long fieldTemplateId);
}
