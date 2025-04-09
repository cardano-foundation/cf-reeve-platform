package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.organisation.domain.core.OrganisationViolation;

@Getter
@Builder
@AllArgsConstructor
public class OrganisationValidationView {

    @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
    private String organisationId;
    private List<OrganisationViolation> violations;
    private boolean isValid;

}
