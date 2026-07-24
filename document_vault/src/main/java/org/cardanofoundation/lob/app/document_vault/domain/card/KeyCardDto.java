package org.cardanofoundation.lob.app.document_vault.domain.card;

import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.Nullable;
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
 * A permissionless key card: "this X25519 public key belongs to this holder, in this organisation."
 * Assembled client-side from a locally generated passkey+PRF key (contract §2.8). There is no issuer
 * and no signature: the backend accepts any well-formed card and the SENDER verifies the recipient's
 * key out-of-band before encrypting to it (trust-on-first-use).
 *
 * NOTE: no `privateKey` field, on purpose (blueprint I5). If a card still carries one, the
 * unknown-field sink below catches it and the request is REJECTED (400 CARD_CONTAINS_PRIVATE_KEY)
 * rather than silently accepted minus the key.
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

    /**
     * Present only on a Veridian-attested card (indexer ceremony); absent = today's unattested card,
     * still valid (trust-on-first-use). Carries what B2 needs to verify the attestation later: the
     * wallet OOBI, the presenting AID, the presented credential/schema SAIDs, and the on-chain
     * CIP-170 ATTEST tx hash. This task (B1) only carries the block through to storage — nothing here
     * verifies it yet.
     */
    @Nullable
    @Valid
    private CardAttestation attestation;

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

    /**
     * @param organisationId the HOLDER's own organisation, as they describe it — a free-form label like
     *                       "Privat", not a Reeve organisation id. Optional: a tool minting a card
     *                       outside Reeve has no organisation to name and should not have to invent one.
     *                       Stored as provenance and shown to senders; never matched against the
     *                       organisation the card is imported into.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subject(@NotNull CardSubjectType subjectType,
                          @NotBlank @Size(max = 255) String subjectId,
                          @NotBlank @Size(max = 255) String displayName,
                          @NotBlank @Email @Size(max = 320) String email,
                          @Nullable @Size(max = 255) String organisationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Key(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$",
                              message = "publicKey must be 32 bytes of lowercase hex.") String publicKey,
                      @NotBlank @Size(max = 255) String label,
                      @NotNull KeyAssurance assurance,
                      @NotBlank @Size(max = 64) String createdAt) {
    }

    /**
     * The result of the indexer's Veridian attestation ceremony (design doc "The card-format
     * contract"), bound to this card. Only ever set by the indexer at export time; the platform stores
     * it as-is on import (provenance) and B2 is what will actually verify it against KERIA/on-chain.
     *
     * @param oobi            the attesting wallet's OOBI — how the platform resolves {@link #aid}.
     * @param aid             the wallet AID that presented the credential and anchored the attestation.
     * @param credentialSaid  SAID of the credential presented during the ceremony.
     * @param schemaSaid      SAID of that credential's schema.
     * @param txHash          Cardano tx hash of the on-chain CIP-170 ATTEST anchoring this card.
     * @param credentialCesr  the full CESR credential chain the wallet presented — carried so the
     *                        platform can re-validate the credential itself (it cannot fetch it via
     *                        the OOBI alone). Nullable: an older attested card may omit it, in which
     *                        case the credential cannot be verified and B2 rejects the card.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardAttestation(@NotBlank String oobi,
                                  @NotBlank String aid,
                                  @NotBlank String credentialSaid,
                                  @NotBlank String schemaSaid,
                                  @NotBlank String txHash,
                                  @Nullable String credentialCesr) {
    }
}
