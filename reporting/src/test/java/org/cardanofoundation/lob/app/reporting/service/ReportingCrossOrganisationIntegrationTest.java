package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

/**
 * A user who belongs to both organisations still can't use one organisation's request on the other's records: records
 * addressed by id are only found within the requested organisation, against the real queries.
 */
@SpringBootTest
// Same context as the other reporting integration test: a second configuration here would be picked up by both scans
@ContextConfiguration(classes = ReportingServiceAggregationIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class ReportingCrossOrganisationIntegrationTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";
    private static final String TEMPLATE_OF_B = "template-of-b";

    @Autowired
    private ReportTemplateService reportTemplateService;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private ReportTemplateRepository reportTemplateRepository;

    @Autowired
    private ReportingRepository reportingRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        ReportTemplateEntity template = new ReportTemplateEntity();
        template.setId(TEMPLATE_OF_B);
        template.setOrganisationId(ORG_B);
        template.setName("Template of B");
        template.setActive(true);
        template.setDataMode(DataMode.SYSTEM);
        template.setFields(List.of());
        template.setValidationRules(List.of());
        template.setReportTemplateType(ReportTemplateType.INCOME_STATEMENT);
        entityManager.persist(template);
        entityManager.flush();
    }

    @Test
    void deletingAnotherOrganisationsTemplate_isNotFoundAndLeavesItInPlace() {
        Either<ProblemDetail, Void> result = reportTemplateService.delete(ORG_A, TEMPLATE_OF_B);

        assertTrue(result.isLeft());
        assertEquals(404, result.getLeft().getStatus());
        entityManager.flush();
        entityManager.clear();
        assertTrue(reportTemplateRepository.findById(TEMPLATE_OF_B).isPresent());
    }

    @Test
    void readingAnotherOrganisationsTemplate_isNotFound() {
        assertTrue(reportTemplateService.findByOrganisationIdAndId(ORG_A, TEMPLATE_OF_B).isEmpty());
        assertTrue(reportTemplateService.findByOrganisationIdAndId(ORG_B, TEMPLATE_OF_B).isPresent());
    }

    @Test
    void anotherOrganisationsReport_isNotFoundForReadOrDelete() {
        ReportEntity report = new ReportEntity();
        report.setId("report-of-b");
        report.setOrganisationId(ORG_B);
        report.setName("Report of B");
        report.setReportTemplate(entityManager.find(ReportTemplateEntity.class, TEMPLATE_OF_B));
        report.setIntervalType(org.cardanofoundation.lob.app.reporting.model.enums.IntervalType.YEAR);
        report.setYear((short) 2025);
        report.setDataMode(DataMode.SYSTEM);
        entityManager.persist(report);
        entityManager.flush();

        assertTrue(reportingService.findByOrganisationIdAndId(ORG_A, "report-of-b").isEmpty());
        Either<ProblemDetail, Void> deleted = reportingService.delete(ORG_A, "report-of-b");

        assertEquals(404, deleted.getLeft().getStatus());
        entityManager.flush();
        entityManager.clear();
        assertTrue(reportingRepository.findById("report-of-b").isPresent());
    }

}
