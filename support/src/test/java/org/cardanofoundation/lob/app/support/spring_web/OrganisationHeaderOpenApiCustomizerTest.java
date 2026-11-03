package org.cardanofoundation.lob.app.support.spring_web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;

import org.junit.jupiter.api.Test;

class OrganisationHeaderOpenApiCustomizerTest {

    private final OrganisationHeaderOpenApiCustomizer customizer = new OrganisationHeaderOpenApiCustomizer();

    @Test
    void addsTheOptionalHeaderToEveryOperation_keepingExistingParameters() {
        Operation get = new Operation().addParametersItem(new Parameter().in("query").name("organisationId"));
        Operation post = new Operation();
        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/a", new PathItem().get(get).post(post))
                .addPathItem("/api/v1/b", new PathItem().delete(new Operation())));

        customizer.customise(openApi);

        assertThat(get.getParameters()).extracting(Parameter::getName).containsExactly("organisationId", "X-Organisation-Id");
        assertThat(post.getParameters()).singleElement().satisfies(header -> {
            assertThat(header.getIn()).isEqualTo("header");
            assertThat(header.getName()).isEqualTo("X-Organisation-Id");
            assertThat(header.getRequired()).isFalse();
            // no example: Swagger would pre-fill it and send requests to that organisation
            assertThat(header.getExample()).isNull();
        });
        assertThat(openApi.getPaths().get("/api/v1/b").getDelete().getParameters()).hasSize(1);
    }

    @Test
    void anApiWithoutPathsIsLeftAlone() {
        assertThatCode(() -> customizer.customise(new OpenAPI())).doesNotThrowAnyException();
    }

}
