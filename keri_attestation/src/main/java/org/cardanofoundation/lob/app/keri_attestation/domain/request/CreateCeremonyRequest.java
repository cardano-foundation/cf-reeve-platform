package org.cardanofoundation.lob.app.keri_attestation.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /ceremonies} (design §4.1/§4.2). Not a {@code BaseRequest} subclass — see
 * {@link ResolveOobiRequest}'s javadoc.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateCeremonyRequest {

    @Schema(description = "Identifies which AttestationTargetProvider owns this ceremony", example = "DOCUMENT")
    @NotBlank
    @Size(max = 64)
    private String targetType;

    @Schema(description = "The target's own id, meaningful only to its AttestationTargetProvider")
    @NotBlank
    private String targetId;
}
