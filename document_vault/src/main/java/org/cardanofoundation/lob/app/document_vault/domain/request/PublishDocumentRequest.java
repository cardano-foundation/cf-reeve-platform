package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Optional publish-endpoint body (design §5.1, Task 14): a bodiless request (no body at all, or an
 * empty object) leaves {@link #attestationCeremonyId} null and publish behaves exactly as before
 * this task. NOT a {@link org.cardanofoundation.lob.app.support.spring_web.BaseRequest} — unlike
 * upload, publish is user-scoped (the target document, not an organisationId payload field, decides
 * which organisation this acts on), so there is no {@code organisationId} to carry.
 *
 * <p><b>{@code ignoreUnknown = false} (M3 cross-review F6 fix), a deliberate deviation from this
 * platform's usual DTO convention of tolerating unknown fields:</b> this body has exactly one
 * meaningful field, and getting its NAME wrong is the one mistake this endpoint must never absorb
 * silently. A client that typos {@code attestationCeremonyId} (e.g. {@code "attestationCeremoyId"})
 * would, under the tolerant convention, see it dropped as an unknown field and silently fall through
 * to a plain, UNATTESTED publish — the exact opposite of what the caller asked for, with no error to
 * signal it. Rejecting the whole request with a 400 instead surfaces the typo immediately, when the
 * only other body this endpoint accepts (bodiless, or {@code {}}) makes a genuinely optional field
 * unambiguous already.
 *
 * <p><b>R4 fix (Codex re-verification): explicit-null detection.</b> {@code
 * {"attestationCeremonyId": null}} names the field but nulls it — ambiguous intent, distinct from
 * simply omitting it (bodiless, or {@code {}}), which stays a plain publish. Once deserialized, both
 * shapes leave {@link #attestationCeremonyId} null, so {@link #attestationCeremonyIdExplicitlySet}
 * captures which one actually happened: {@link #setAttestationCeremonyId} only ever runs when
 * Jackson finds the property in the request JSON at all — value present or explicit JSON
 * {@code null} — never for an absent property, so the flag distinguishes exactly "named" from
 * "omitted". {@code VaultDocumentController} is responsible for actually rejecting the explicit-null
 * case; this class only makes the distinction observable.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class PublishDocumentRequest {

    @Schema(description = "A completed KERI wallet-attestation ceremony id to consume as part of this publish (optional; omit for a plain publish)")
    @Size(max = 64)
    private String attestationCeremonyId;

    /** R4 fix: true once {@link #setAttestationCeremonyId} has run at all (i.e. the request JSON
     *  named {@code attestationCeremonyId}, whether with a real value or an explicit {@code null}) —
     *  never part of the JSON contract itself. */
    @JsonIgnore
    private boolean attestationCeremonyIdExplicitlySet;

    /**
     * {@code nulls = Nulls.SET} pins Jackson to always invoke this setter (with {@code null}) for an
     * explicit {@code "attestationCeremonyId": null} in the request body, rather than silently
     * skipping the call — Jackson's alternative {@code Nulls.SKIP} would leave both the field and
     * {@link #attestationCeremonyIdExplicitlySet} untouched for that case, making it indistinguishable
     * from a bodiless request at the getter level.
     */
    @JsonSetter(nulls = Nulls.SET)
    public void setAttestationCeremonyId(String attestationCeremonyId) {
        this.attestationCeremonyId = attestationCeremonyId;
        this.attestationCeremonyIdExplicitlySet = true;
    }

}
