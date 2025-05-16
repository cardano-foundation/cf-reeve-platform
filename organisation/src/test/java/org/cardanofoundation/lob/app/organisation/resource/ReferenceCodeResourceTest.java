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

import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.service.OrganisationService;
import org.cardanofoundation.lob.app.organisation.service.ReferenceCodeService;

@ExtendWith(MockitoExtension.class)
class ReferenceCodeResourceTest {

    @Mock
    private ReferenceCodeService referenceCodeService;
    @Mock
    private OrganisationService organisationService;

    @InjectMocks
    private ReferenceCodeResource controller;

    @Test
    void insertReferenceCodeByCsv_error() {
        when(referenceCodeService.insertReferenceCodeByCsv("orgId", null)).thenReturn(Either.left(Set.of(Problem.builder()
                .withTitle("Error")
                .withStatus(Status.BAD_REQUEST)
                .build())));

        ResponseEntity<?> response = controller.insertRefCodeByCsv("orgId", null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isInstanceOf(Set.class);
        assertThat(((Set<?>) response.getBody())).hasSize(1);
    }

    @Test
    void insertReferenceCodeByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        ReferenceCodeView view = mock(ReferenceCodeView.class);
        when(referenceCodeService.insertReferenceCodeByCsv("orgId", file)).thenReturn(Either.right(Set.of(view)));

        ResponseEntity<?> response = controller.insertRefCodeByCsv("orgId", file);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(Set.class);
        assertThat(((Set<?>) response.getBody())).hasSize(1);
        assertThat(((Set<?>) response.getBody()).iterator().next()).isEqualTo(view);
    }

}
