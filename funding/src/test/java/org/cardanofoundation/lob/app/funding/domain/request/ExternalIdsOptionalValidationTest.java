package org.cardanofoundation.lob.app.funding.domain.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

/** externalProjectId is legacy: it must never be required, but is still accepted when a client sends it. */
class ExternalIdsOptionalValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rootRequest_isValidWithoutExternalProjectId() {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").projectTitle("Project Atlas").proId("PRJ-2000")
                .totalAmount(new BigDecimal("50000.00")).currency("ADA").build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rootRequest_stillAcceptsExternalProjectId() {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").externalProjectId("PROJ-ATLAS").projectTitle("Project Atlas")
                .totalAmount(new BigDecimal("50000.00")).build();

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.getExternalProjectId()).isEqualTo("PROJ-ATLAS");
    }

    @Test
    void subProjectNode_isValidWithoutExternalProjectId() {
        ProjectTreeNodeRequest node = ProjectTreeNodeRequest.builder().projectTitle("Sub B").proId("PRJ-2000-S2")
                .action("DELETE").build();

        assertThat(validator.validate(node)).isEmpty();
    }

    @Test
    void nestedSubProjectWithoutExternalProjectId_isValidInsideARootRequest() {
        ProjectWithMilestonesCreateRequest request = ProjectWithMilestonesCreateRequest.builder()
                .organisationId("org1").projectTitle("Project Atlas").totalAmount(new BigDecimal("50000.00"))
                .subProjects(List.of(ProjectTreeNodeRequest.builder().projectTitle("Sub B").proId("PRJ-2000-S2")
                        .action("DELETE").build()))
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void projectTitleIsStillRequired() {
        ProjectTreeNodeRequest node = ProjectTreeNodeRequest.builder().proId("PRJ-2000-S2").action("DELETE").build();

        assertThat(validator.validate(node)).extracting(ConstraintViolation::getMessage).isNotEmpty();
    }
}
