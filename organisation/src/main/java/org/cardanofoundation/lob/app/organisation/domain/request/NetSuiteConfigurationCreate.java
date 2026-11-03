package org.cardanofoundation.lob.app.organisation.domain.request;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class NetSuiteConfigurationCreate {

    @NotBlank
    @Schema(example = "https://1234567.restlets.api.netsuite.com/app/site/hosting/restlet.nl?script=123&deploy=1")
    private String baseUrl;

    @NotBlank
    @Schema(example = "https://1234567.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token")
    private String tokenUrl;

    @NotBlank
    @Schema(example = "b9c1f0e2")
    private String clientId;

    @NotBlank
    @Schema(example = "a1b2c3d4")
    private String certificateId;

    /** PKCS#8 PEM. Write-only: never returned by any endpoint, never logged. */
    @NotBlank
    @ToString.Exclude
    @Schema(description = "PKCS#8 PEM private key. Write-only — never returned.")
    private String privateKey;

}
