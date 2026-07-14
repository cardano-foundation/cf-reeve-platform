package org.cardanofoundation.lob.app.document_vault.domain.card;

import java.util.HashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * An Ed25519-signed statement: "this X25519 public key belongs to this holder, in this organisation,
 * and I — the issuer — vouch for it" (contract §2.8). Minted by the Indexer.
 *
 * NOTE: no `privateKey` field, on purpose (blueprint I5). A handover card carries one; the client
 * strips it before import, and if it does not, the unknown-field sink below catches it and the
 * request is REJECTED (400 CARD_CONTAINS_PRIVATE_KEY) rather than silently accepted minus the key.
 */
@Getter
@Setter
@NoArgsConstructor
public class KeyCardDto {

    @Schema(description = "Card wire-format version.")
    private int v;

    @NotBlank
    private String type;

    @NotNull
    @Valid
    private Subject subject;

    @NotNull
    @Valid
    private Key key;

    @NotNull
    @Valid
    private Issuer issuer;

    @Schema(description = "Ed25519 signature over the length-prefixed signing input (contract §2.8.3).")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{128}$", message = "signature must be 64 bytes of lowercase hex.")
    private String signature;

    /** Everything we do not model — including a `privateKey` section, which must be rejected. */
    private final Map<String, Object> unknown = new HashMap<>();

    @JsonAnySetter
    public void putUnknown(String name, Object value) {
        unknown.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> getUnknown() {
        return unknown;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subject(@NotNull CardSubjectType subjectType,
                          @NotBlank @Size(max = 255) String subjectId,
                          @NotBlank @Size(max = 255) String displayName,
                          @NotBlank @Email @Size(max = 320) String email,
                          @NotBlank String organisationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Key(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$",
                              message = "publicKey must be 32 bytes of lowercase hex.") String publicKey,
                      @NotBlank @Size(max = 255) String label,
                      @NotNull KeyAssurance assurance,
                      @NotBlank @Size(max = 64) String createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issuer(@NotBlank @Size(max = 64) String issuerId,
                         @NotBlank String algorithm,
                         @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$",
                                 message = "issuer publicKey must be 32 bytes of lowercase hex.")
                         String publicKey) {
    }
}
