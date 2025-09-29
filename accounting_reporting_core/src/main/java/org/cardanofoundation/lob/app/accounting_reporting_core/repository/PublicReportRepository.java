package org.cardanofoundation.lob.app.accounting_reporting_core.repository;


import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.FINALIZED;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;

@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class PublicReportRepository {
    private final EntityManager em;


    public Set<ReportEntity> findAllByTypeAndPeriod(String organisationId, ReportType reportType, IntervalType intervalType, short year, short period) {
        String query = """
                SELECT r FROM accounting_reporting_core.report.ReportEntity r
                LEFT JOIN accounting_reporting_core.report.ReportEntity r2 on r.idControl = r2.idControl and r.ver < r2.ver
                """;

        String where = """
                WHERE r2.idControl IS NULL
                AND r.ledgerDispatchStatus = '%s'
                AND r.organisation.id = '%s'
                """.formatted(FINALIZED, organisationId);

        if (null != reportType) {
            where += """
             AND r.type = '%s'
             """.formatted(reportType);
        }
        if (null != intervalType) {
            where += """
             AND r.intervalType = '%s'
             AND r.year = %d
             AND r.period = %d
             """.formatted(intervalType, year, period);
        }

        where += """
                ORDER BY r.createdAt ASC, r.reportId ASC
                """;


        jakarta.persistence.Query resultQuery = em.createQuery(query + where);

        return new HashSet<ReportEntity>(resultQuery.getResultList());
    }
}
