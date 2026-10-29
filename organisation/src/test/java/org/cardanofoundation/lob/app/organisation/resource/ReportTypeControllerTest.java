package org.cardanofoundation.lob.app.organisation.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.request.ReportTypeFieldUpdate;
import org.cardanofoundation.lob.app.organisation.service.ReportTypeService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class ReportTypeControllerTest {

    @Mock
    private ReportTypeService reportTypeService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @InjectMocks
    private ReportTypeController controller;

    @BeforeEach
    void setUp() {
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
    }

    @Test
    void insertReferenceCodeByCsv_error() {
        MultipartFile file = mock(MultipartFile.class);
        when(reportTypeService.addMappingToReportTypeFieldCsv("orgId", file)).thenReturn(Either.left(List.of(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Detail"))));

        ResponseEntity<?> response = controller.addMappingToReportTypeField("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(List.class);
        assertThat(((List<?>) response.getBody())).hasSize(1);
    }

    @Test
    void insertReferenceCodeByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        when(reportTypeService.addMappingToReportTypeFieldCsv("orgId", file)).thenReturn(Either.right(null));

        ResponseEntity<?> response = controller.addMappingToReportTypeField("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void getReferenceCodes_noOrgAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.getReferenceCodes("orgId");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(reportTypeService);
    }

    @Test
    void addMappingToReportTypeField_noOrgAccess() {
        ReportTypeFieldUpdate update = mock(ReportTypeFieldUpdate.class);
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.addMappingToReportTypeField("orgId", update);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(reportTypeService);
    }

    @Test
    void addMappingToReportTypeFieldCsv_noOrgAccess() {
        MultipartFile file = mock(MultipartFile.class);
        when(keycloakSecurityHelper.canUserAccessOrg("orgId")).thenReturn(false);

        ResponseEntity<?> response = controller.addMappingToReportTypeField("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(reportTypeService);
    }
}
