package org.cardanofoundation.lob.app.funding.domain.request;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

/**
 * Request body for publishing several funding events of one organisation in a single call. Each id is
 * published under the same rules as single-event publish; ids that cannot be published are reported
 * back as skipped instead of failing the whole request. Duplicate ids are collapsed into one outcome.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class SpendingEventBulkPublishRequest extends BaseRequest {

    @Schema(description = "Ids of the events to publish (internal event ids, as returned by GET /events).",
            example = "[\"8b3753dda23452180bf502db991bcd2ccbf30e648a9b84778477c0d2ee618dfa\"]")
    @NotEmpty(message = "At least one event id is required.")
    @Builder.Default
    private List<@NotBlank(message = "Event ids must not be blank.") String> eventIds = new ArrayList<>();

}
