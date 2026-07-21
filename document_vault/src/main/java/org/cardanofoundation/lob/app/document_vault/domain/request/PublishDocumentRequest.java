package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optional publish-endpoint body (design §5.1, Task 14): a bodiless request (no body at all, or an
 * empty object) leaves {@link #attestationCeremonyId} null and publish behaves exactly as before
 * this task. NOT a {@link org.cardanofoundation.lob.app.support.spring_web.BaseRequest} — unlike
 * upload, publish is user-scoped (the target document, not an organisationId payload field, decides
 * which organisation this acts on), so there is no {@code organisationId} to carry.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PublishDocumentRequest {

    @Schema(description = "A completed KERI wallet-attestation ceremony id to consume as part of this publish (optional; omit for a plain publish)")
    @Size(max = 64)
    private String attestationCeremonyId;

}
