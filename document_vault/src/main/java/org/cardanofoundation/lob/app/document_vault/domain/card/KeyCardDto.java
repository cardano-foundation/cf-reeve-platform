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
 * Assembled client-side from a locally generated passkey+PRF key. There is no issuer and no
 * signature: the backend accepts any well-formed card and the sender verifies the recipient's key
 * out-of-band before encrypting to it (trust-on-first-use).
 *
 * <p>There is deliberately no {@code privateKey} field. A card carrying one lands in the
 * unknown-field sink below and the request is rejected rather than accepted minus the key.
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
     * Present only on a Veridian-attested card; absent on an unattested one, which remains valid
     * under trust-on-first-use. Carries everything needed to verify the attestation at import: the
     * wallet OOBI, the presenting AID, the credential and schema SAIDs, and the KEL event the wallet
     * anchored the attestation in.
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
     * <p>Unknown fields are REJECTED here, unlike the attestation block below. Everything in this record
     * is covered by the issuer's attestation digest, so a field this server does not know about is
     * either a secret smuggled alongside the public key or proof that the digest formula has diverged —
     * and silently dropping it would turn both into a verification failure with no explanation.
     *
     * @param organisationId the HOLDER's own organisation, as they describe it — a free-form label like
     *                       "Privat", not a Reeve organisation id. Optional: a tool minting a card
     *                       outside Reeve has no organisation to name and should not have to invent one.
     *                       Stored as provenance and shown to senders; never matched against the
     *                       organisation the card is imported into.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Subject(@NotNull CardSubjectType subjectType,
                          @NotBlank @Size(max = 255) String subjectId,
                          @Nullable @Size(max = 255) String displayName,
                          @Nullable @Email @Size(max = 320) String email,
                          @Nullable @Size(max = 255) String organisationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Key(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$",
                              message = "publicKey must be 32 bytes of lowercase hex.") String publicKey,
                      @Nullable @Size(max = 255) String label,
                      @NotNull KeyAssurance assurance,
                      @NotBlank @Size(max = 64) String createdAt) {
    }

    /**
     * The result of the issuing indexer's Veridian attestation ceremony, bound to this card. Set at
     * export time and re-verified against KERIA on import.
     *
     * <p>Nothing about this attestation is on-chain. The attesting wallet anchors it in its own KEL as
     * an interaction-event seal, and that seal IS the attestation — so verification needs the event's
     * coordinates, not a transaction hash. Cards issued before this format carried a {@code txHash}
     * instead; it is ignored on read rather than rejected.
     *
     * <p>{@code cardDigest} and {@code payloadSaid} are informational. A verifier recomputes both from
     * the card body and compares — it must never verify against the card's own claim of them.
     *
     * @param oobi           the attesting wallet's OOBI — how the platform resolves {@link #aid}.
     * @param aid            the wallet AID that presented the credential and anchored the attestation.
     * @param credentialSaid SAID of the credential presented during the ceremony.
     * @param schemaSaid     SAID of that credential's schema.
     * @param kelSequence    sequence number of the anchoring KEL interaction event.
     * @param kelEventSaid   SAID of that event. Together with {@code kelSequence} it names the exact
     *                       event the verifier must find; a seal-matching event elsewhere in the KEL
     *                       does not substitute.
     * @param metadataLabel  the CIP-170 metadata label the ceremony ran under, as the exact string fed
     *                       into the signed payload. An input to {@code payloadSaid}, so a verifier
     *                       must take it from here rather than assume the default.
     * @param cardDigest     the issuer's canonical digest over this card minus the attestation block.
     * @param payloadSaid    SAID of the saidified remotesign payload the wallet sealed.
     * @param credentialCesr the full CESR credential chain the wallet presented, carried because the
     *                       credential cannot be fetched through the OOBI alone. Nullable: an older
     *                       attested card may omit it, and is then rejected as unverifiable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardAttestation(@NotBlank @Size(max = 2048) String oobi,
                                  @NotBlank @Size(max = 255) String aid,
                                  @Nullable @Size(max = 255) String credentialSaid,
                                  @Nullable @Size(max = 255) String schemaSaid,
                                  @Nullable @Size(max = 32) String kelSequence,
                                  @Nullable @Size(max = 255) String kelEventSaid,
                                  @Nullable @Size(max = 32) String metadataLabel,
                                  @Nullable @Size(max = 255) String cardDigest,
                                  @Nullable @Size(max = 255) String payloadSaid,
                                  // Bounded like every other card string: this one is parsed and
                                  // walked synchronously, so an unbounded value is work an
                                  // unauthenticated caller gets to choose the size of.
                                  @Nullable @Size(max = 262144) String credentialCesr) {
    }
}
