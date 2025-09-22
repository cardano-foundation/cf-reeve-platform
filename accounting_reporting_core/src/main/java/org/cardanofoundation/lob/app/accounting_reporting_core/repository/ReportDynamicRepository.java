package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportDynamicEntity;


public interface ReportDynamicRepository extends JpaRepository<ReportDynamicEntity, String> {


}
