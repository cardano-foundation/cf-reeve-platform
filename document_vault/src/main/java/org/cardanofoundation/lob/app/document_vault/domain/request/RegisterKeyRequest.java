package org.cardanofoundation.lob.app.document_vault.domain.request;


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

    // No e-mail: an organisation key belongs to a Keycloak user, and the account is already the
    // contact. Asking for one here would invite a second, unverified address for the same person.
    // Addressbook entries do carry one — nobody logs in as a contact, so it is all there is.

    @Size(max = 512)
    private String credentialId;
}
