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
 * Optional body for the publish endpoint. A bodiless request — no body at all, or an empty object —
 * leaves {@link #attestationCeremonyId} null and publish stays plain. Not a
 * {@link org.cardanofoundation.lob.app.support.spring_web.BaseRequest}: publish is scoped by the
 * target document rather than by an {@code organisationId} field.
 *
 * <p>{@code ignoreUnknown = false} deviates from the usual convention of tolerating unknown fields.
 * This body has one meaningful field, and a client that misspells it would otherwise have it dropped
 * and silently fall through to an unattested publish — the opposite of what was asked, with nothing
 * to signal it. Rejecting the request surfaces the typo instead.
 *
 * <p>{@code {"attestationCeremonyId": null}} names the field but nulls it, which is distinct from
 * omitting it. Both shapes leave the field null once deserialized, so
 * {@link #attestationCeremonyIdExplicitlySet} records which one occurred; {@code VaultDocumentController}
 * decides what to do with it.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class PublishDocumentRequest {

    @Schema(description = "A completed KERI wallet-attestation ceremony id to consume as part of this publish (optional; omit for a plain publish)")
    @Size(max = 64)
    private String attestationCeremonyId;

    /** True once {@link #setAttestationCeremonyId} has run at all, meaning the request JSON named the
     *  field with either a value or an explicit null. Never part of the JSON contract. */
    @JsonIgnore
    private boolean attestationCeremonyIdExplicitlySet;

    /**
     * {@code Nulls.SET} makes Jackson invoke this setter even for an explicit JSON null. Under
     * {@code Nulls.SKIP} the call would be skipped, leaving
     * {@link #attestationCeremonyIdExplicitlySet} false and an explicit null indistinguishable from an
     * omitted field.
     */
    @JsonSetter(nulls = Nulls.SET)
    public void setAttestationCeremonyId(String attestationCeremonyId) {
        this.attestationCeremonyId = attestationCeremonyId;
        this.attestationCeremonyIdExplicitlySet = true;
    }

}
