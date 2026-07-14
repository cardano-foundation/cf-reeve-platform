package org.cardanofoundation.lob.app.document_vault.domain.request;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegisterKeyRequest extends BaseRequest {
    // organisationId comes from BaseRequest (single org — one key entry per organisation, product
    // decision) and is therefore also validated by the OrganisationCheckInterceptor.

    @NotBlank
    @Size(max = 255)
    private String label;

    @Schema(description = "X25519 public key, 32 bytes lowercase hex")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "publicKey must be 32 bytes of lowercase hex.")
    private String publicKey;

    @Schema(description = "Notification e-mail (addressbook). Stays server-side — never exported to IPFS or L1.")
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    @Size(max = 512)
    private String credentialId;
}
