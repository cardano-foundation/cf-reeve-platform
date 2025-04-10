package org.cardanofoundation.lob.app.organisation.service.validation;

import java.util.List;
import java.util.Optional;

import org.cardanofoundation.lob.app.organisation.domain.core.OrganisationViolation;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

public interface OrganisationValidationRule {

    Optional<List<OrganisationViolation>> validate(Organisation organisation);

}
