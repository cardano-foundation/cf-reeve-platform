package org.cardanofoundation.lob.app.document_vault.domain.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
public class UploadDocumentRequest extends BaseRequest {

    @Schema(description = "Envelope wire-format version; only 1 is supported")
    @NotNull
    private Integer envelopeVersion;

    @Size(max = 255)
    private String fileName;

    @Size(max = 255)
    private String contentType;

    @Size(max = 1024)
    private String description;

    @Schema(description = "SHA-256 commitment over the plaintext, computed client-side; opaque to the server")
    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "plaintextHash must be 32 bytes of lowercase hex.")
    private String plaintextHash;

    @NotNull
    @Valid
    private PayloadRequest payload;

    @NotEmpty
    @Valid
    private List<SlotRequest> slots;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayloadRequest {

        @Schema(description = "AES-256-GCM ciphertext, base64")
        @NotBlank
        private String ciphertext;

        @Schema(description = "GCM nonce, 12 bytes lowercase hex")
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{24}$", message = "nonce must be 12 bytes of lowercase hex.")
        private String nonce;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SlotRequest {

        @NotBlank
        private String keyId;

        /**
         * A label only, never a trust anchor (I6) — but the obvious thing for a client to put here is
         * the recipient's accountId, so it tracks that column's width rather than the old 255: an
         * imported id carries an 'ext:' prefix and can reach 259.
         */
        @NotBlank
        @Size(max = 260)
        private String recipientRef;

        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "ephemeralPub must be 32 bytes of lowercase hex.")
        private String ephemeralPub;

        @Schema(description = "AES-256-GCM-wrapped DEK (encrypted; the server cannot unwrap it)")
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{96}$", message = "wrappedDek must be 48 bytes of lowercase hex.")
        private String wrappedDek;
    }
}
