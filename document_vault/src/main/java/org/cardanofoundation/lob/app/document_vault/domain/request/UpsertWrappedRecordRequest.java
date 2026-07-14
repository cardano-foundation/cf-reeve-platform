package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpsertWrappedRecordRequest {

    @Schema(description = "Opaque, client-encrypted wrapped-key record. Stored and returned verbatim.")
    @NotBlank
    private String record;

    @Min(1)
    private int version;
}
