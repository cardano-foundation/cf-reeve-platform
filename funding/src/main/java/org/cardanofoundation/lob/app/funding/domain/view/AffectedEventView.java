package org.cardanofoundation.lob.app.funding.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import io.swagger.v3.oas.annotations.media.Schema;

/** An event named in a cascade-delete/cleanup response, so a UI can link the human to it. LOB-2365. */
@Getter
@Builder
@AllArgsConstructor
public class AffectedEventView {

    @Schema(example = "8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa",
            description = "Internal event id — use with GET /api/v1/funding/events/{eventId} to build a link to it.")
    private String eventId;

    @Schema(example = "GRANT-2025-001", description = "User-facing funding reference, for display.")
    private String fundingId;

}
