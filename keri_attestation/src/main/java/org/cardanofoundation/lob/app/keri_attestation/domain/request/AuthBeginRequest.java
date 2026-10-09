package org.cardanofoundation.lob.app.keri_attestation.domain.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /ceremonies/{id}/auth-begin}. Not a {@code BaseRequest} subclass —
 * see {@link ResolveOobiRequest}'s javadoc. Both fields are optional: {@code assumePublished} accepts
 * AUTH_BEGIN as already published WITHOUT any on-chain verification (trusts the caller), and otherwise
 * a fresh AUTH_BEGIN transaction is handed to {@code blockchain_publisher} for publication.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthBeginRequest {

    @Schema(description = "Assert AUTH_BEGIN is already published on-chain and accept it WITHOUT on-chain "
            + "verification (trusts the caller — see server-side policy TODO)")
    private boolean assumePublished;

    @Schema(description = "Retry the step currently being waited on, after its cooldown has elapsed")
    private boolean retry;
}
