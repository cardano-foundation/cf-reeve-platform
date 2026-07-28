package org.cardanofoundation.lob.app.keri_attestation.domain.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /ceremonies/{id}/auth-begin}. Not a {@code BaseRequest} subclass —
 * see {@link ResolveOobiRequest}'s javadoc. All fields are optional and select one of three paths:
 * a present {@code externalTxHash} verifies the given tx already establishes on-chain signing authority
 * ("the skip"); an absent/blank hash with {@code assumePublished} true accepts AUTH_BEGIN as already
 * published WITHOUT any on-chain verification (trusts the caller); an absent/blank hash otherwise
 * submits a fresh AUTH_BEGIN transaction.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthBeginRequest {

    @Schema(description = "An already-confirmed AUTH_BEGIN tx hash to verify instead of submitting a fresh one")
    private String externalTxHash;

    @Schema(description = "With no externalTxHash, assert AUTH_BEGIN is already published on-chain and accept it "
            + "WITHOUT on-chain verification (trusts the caller — see server-side policy TODO)")
    private boolean assumePublished;

    @Schema(description = "Retry the step currently being waited on, after its cooldown has elapsed")
    private boolean retry;
}
