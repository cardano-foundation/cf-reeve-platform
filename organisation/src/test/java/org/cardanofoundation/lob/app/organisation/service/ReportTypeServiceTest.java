package org.cardanofoundation.lob.app.organisation.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.csv.ReportTypeFieldUpdateCsv;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeFieldRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class ReportTypeServiceTest {

    @Mock
    private ReportTypeRepository reportTypeRepository;
    @Mock
    private ReportTypeFieldRepository reportTypeFieldRepository;
    @Mock
    private OrganisationChartOfAccountSubTypeRepository organisationChartOfAccountSubTypeRepository;
    @Mock
    private CsvParser<ReportTypeFieldUpdateCsv> csvParser;

    @InjectMocks
    private ReportTypeService reportTypeService;

    @Test
    void addMappingToReportTypeFieldCsv_parseError() {
        String orgId = "orgId";
        MultipartFile file = mock(MultipartFile.class);

        when(csvParser.parseCsv(file, ReportTypeFieldUpdateCsv.class)).thenReturn(Either.left(Problem.builder()
                .withTitle("CSV_PARSE_ERROR")
                .withStatus(Status.BAD_REQUEST)
                .build()));

        Either<Set<Problem>, Void> result = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file);

        Assertions.assertTrue(result.isLeft());
        Assertions.assertEquals(1, result.getLeft().size());
        Assertions.assertEquals("CSV_PARSE_ERROR", result.getLeft().iterator().next().getTitle());
    }

    @Test
    void addMappingToReportFieldCsv_mapError() {
        String orgId = "orgId";
        MultipartFile file = mock(MultipartFile.class);
        ReportTypeFieldUpdateCsv reportTypeFieldUpdateCsv = mock(ReportTypeFieldUpdateCsv.class);
        when(csvParser.parseCsv(file, ReportTypeFieldUpdateCsv.class)).thenReturn(Either.right(List.of(reportTypeFieldUpdateCsv)));

        Either<Set<Problem>, Void> voids = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file);

        Assertions.assertTrue(voids.isLeft());
        Assertions.assertEquals(1, voids.getLeft().size());
        Assertions.assertEquals(Status.BAD_REQUEST, voids.getLeft().iterator().next().getStatus());
    }

    @Test
    void addMappingToReportFieldCsv_insertError() {
        String orgId = "orgId";
        MultipartFile file = mock(MultipartFile.class);
        ReportTypeFieldUpdateCsv updateCsv = mock(ReportTypeFieldUpdateCsv.class);
        ReportTypeEntity reportTypeEntity = mock(ReportTypeEntity.class);
        ReportTypeFieldEntity reportTypeFieldEntity = mock(ReportTypeFieldEntity.class);
        OrganisationChartOfAccountSubType subType = mock(OrganisationChartOfAccountSubType.class);
        when(csvParser.parseCsv(file, ReportTypeFieldUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));
        when(updateCsv.getReportType()).thenReturn("ReportType");
        when(reportTypeRepository.findByOrganisationAndReportName(orgId, "ReportType")).thenReturn(Optional.ofNullable(reportTypeEntity));
        when(reportTypeEntity.getId()).thenReturn(1L);
        when(updateCsv.getReportTypeField()).thenReturn("FieldId");
        when(reportTypeFieldRepository.findFirstByReportIdAndName(1L, "FieldId")).thenReturn(Optional.ofNullable(reportTypeFieldEntity));
        when(updateCsv.getSubType()).thenReturn("SubTypeId");
        when(organisationChartOfAccountSubTypeRepository.findFirstByName("SubTypeId")).thenReturn(Optional.of(subType));

        Either<Set<Problem>, Void> voids = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file);

        Assertions.assertTrue(voids.isLeft());
        Assertions.assertEquals(1, voids.getLeft().size());
        Assertions.assertEquals("Report Type not found", voids.getLeft().iterator().next().getTitle());

        when(reportTypeRepository.findByOrganisationIdAndId(orgId, 1L)).thenReturn(Optional.of(reportTypeEntity));

        voids = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file);

        Assertions.assertTrue(voids.isLeft());
        Assertions.assertEquals(1, voids.getLeft().size());
        Assertions.assertEquals("Report Type Field not found", voids.getLeft().iterator().next().getTitle());

        when(reportTypeFieldRepository.findByReportIdAndId(1L, 0L)).thenReturn(Optional.of(reportTypeFieldEntity));
        voids = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file);

        Assertions.assertTrue(voids.isLeft());
        Assertions.assertEquals(1, voids.getLeft().size());
        Assertions.assertEquals("Organisation Chart Of Account Sub Type not found", voids.getLeft().iterator().next().getTitle());

        when(subType.getId()).thenReturn(2L);
        when(organisationChartOfAccountSubTypeRepository.findById("2")).thenReturn(Optional.of(subType));

        voids = reportTypeService.addMappingToReportTypeFieldCsv(orgId, file);

        Assertions.assertTrue(voids.isRight());
    }
}
