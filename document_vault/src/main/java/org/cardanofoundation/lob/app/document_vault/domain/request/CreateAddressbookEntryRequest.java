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

/**
 * Add a contact by hand, when you have someone's public key but no key card for it.
 *
 * No assurance field: the tier says how a private key was born, and nobody typing a key into a form can
 * attest to that. A hand-entered entry keeps a null assurance, which renders as "unknown". Import a card
 * if the holder has one — at least its claim is attributable.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateAddressbookEntryRequest extends BaseRequest {
    // organisationId comes from BaseRequest, so OrganisationCheckInterceptor guards it too.

    @NotBlank
    @Size(max = 255)
    private String displayName;

    @Schema(description = "Contact e-mail. Optional. Stays server-side — never exported to IPFS or L1.")
    @Email
    @Size(max = 320)
    private String email;

    @Size(max = 255)
    private String description;

    @Schema(description = "X25519 public key, 32 bytes lowercase hex")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "publicKey must be 32 bytes of lowercase hex.")
    private String publicKey;
}
