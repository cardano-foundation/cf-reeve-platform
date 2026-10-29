package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Correct a contact's details. Only what a human wrote about the contact is editable.
 *
 * Deliberately absent: {@code publicKey}. The key IS the entry — repointing it in place would silently
 * redirect a contact a sender already verified out-of-band, which is a key-substitution attack wearing
 * an edit form. To change a key, delete the entry and add the new one, so the sender is forced to verify
 * again.
 *
 * Also absent: {@code organisationId}, which the stored entry already determines (accepting one here
 * would let the two disagree), and {@code assurance}/{@code homeOrganisationId}, which are provenance —
 * a record of what a card claimed, not fields to be tidied up afterwards.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateAddressbookEntryRequest {

    @NotBlank
    @Size(max = 255)
    private String displayName;

    @Schema(description = "Contact e-mail. Optional. Stays server-side — never exported to IPFS or L1.")
    @Email
    @Size(max = 320)
    private String email;

    @Size(max = 255)
    private String description;
}
