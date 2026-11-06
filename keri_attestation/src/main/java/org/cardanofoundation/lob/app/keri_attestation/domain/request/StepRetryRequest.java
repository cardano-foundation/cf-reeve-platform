package org.cardanofoundation.lob.app.keri_attestation.domain.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of the two step-POSTs that take nothing but a retry flag: {@code /ceremonies/{id}/credential/request}
 * and {@code /ceremonies/{id}/attest}. Not a {@code BaseRequest} subclass — see
 * {@link ResolveOobiRequest}'s javadoc.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StepRetryRequest {

    @Schema(description = "Retry the step currently being waited on, after its cooldown has elapsed")
    private boolean retry;
}
