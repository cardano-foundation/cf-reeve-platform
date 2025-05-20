package org.cardanofoundation.lob.app.organisation.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.service.ReportTypeService;

@ExtendWith(MockitoExtension.class)
class ReportTypeControllerTest {

    @Mock
    private ReportTypeService reportTypeService;

    @InjectMocks
    private ReportTypeController controller;

    @Test
    void insertReferenceCodeByCsv_error() {
        MultipartFile file = mock(MultipartFile.class);
        when(reportTypeService.addMappingToReportTypeFieldCsv("orgId", file)).thenReturn(Either.left(Set.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.BAD_REQUEST)
                .build())));

        ResponseEntity<?> response = controller.addMappingToReportTypeField("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(Set.class);
        assertThat(((Set<?>) response.getBody())).hasSize(1);
    }

    @Test
    void insertReferenceCodeByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        when(reportTypeService.addMappingToReportTypeFieldCsv("orgId", file)).thenReturn(Either.right(null));

        ResponseEntity<?> response = controller.addMappingToReportTypeField("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
