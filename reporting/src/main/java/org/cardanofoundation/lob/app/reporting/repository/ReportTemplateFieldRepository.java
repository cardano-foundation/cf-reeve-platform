package org.cardanofoundation.lob.app.reporting.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;

@Repository
public interface ReportTemplateFieldRepository extends JpaRepository<ReportTemplateFieldEntity, Long> {

    List<ReportTemplateFieldEntity> findByReportTemplateId(String reportTemplateId);

    List<ReportTemplateFieldEntity> findByReportTemplateIdAndParentFieldIsNull(String reportTemplateId);
}
