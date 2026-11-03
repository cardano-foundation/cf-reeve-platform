package org.cardanofoundation.lob.app.support.spring_web;

import org.springframework.stereotype.Component;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;

import org.cardanofoundation.lob.app.support.security.access.CurrentUserAccess;

/**
 * Shows the organisation header on every endpoint in Swagger: permissions are checked in the organisation it selects,
 * but it's read by the security layer, so springdoc can't find it in the controller methods.
 */
@Component
public class OrganisationHeaderOpenApiCustomizer implements OpenApiCustomizer {

    private static final String DESCRIPTION = "Organisation this request acts on; permissions are checked in it. "
            + "Needed when the user belongs to more than one organisation.";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> operation.addParametersItem(organisationHeader())));
    }

    private static Parameter organisationHeader() {
        return new Parameter()
                .in("header")
                .name(CurrentUserAccess.ORGANISATION_HEADER)
                .description(DESCRIPTION)
                .required(false)
                .schema(new StringSchema());
    }

}
