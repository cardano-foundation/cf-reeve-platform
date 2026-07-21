package org.cardanofoundation.lob.app.keri_attestation.domain.request;

import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /identity/oobi/resolve} (design §4.7). Not a {@code BaseRequest} subclass: this
 * module links a KERI identity to a Keycloak user, not to an organisation — there is no
 * {@code organisationId} in scope here.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolveOobiRequest {

    @Schema(description = "The wallet's OOBI URL, containing an /oobi/{aid} path segment")
    @NotBlank
    private String oobiUrl;

    @Schema(description = "Required to switch to a different AID once already linked (design §4.7)")
    private boolean relink;
}
