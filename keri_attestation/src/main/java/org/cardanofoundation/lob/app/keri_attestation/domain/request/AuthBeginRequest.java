package org.cardanofoundation.lob.app.keri_attestation.domain.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /ceremonies/{id}/auth-begin} (design §4.5). Not a {@code BaseRequest} subclass —
 * see {@link ResolveOobiRequest}'s javadoc. Both fields are optional: an absent (or blank)
 * {@code externalTxHash} submits a fresh AUTH_BEGIN transaction; a present one verifies the given tx
 * already establishes on-chain signing authority instead ("the skip").
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthBeginRequest {

    @Schema(description = "An already-confirmed AUTH_BEGIN tx hash to verify instead of submitting a fresh one")
    private String externalTxHash;

    @Schema(description = "Retry the step currently being waited on, after its cooldown has elapsed")
    private boolean retry;
}
