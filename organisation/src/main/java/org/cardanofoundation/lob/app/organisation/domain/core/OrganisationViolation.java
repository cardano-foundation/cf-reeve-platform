package org.cardanofoundation.lob.app.organisation.domain.core;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class OrganisationViolation {

    private OrganisationViolationType type;
    private String message;

}
