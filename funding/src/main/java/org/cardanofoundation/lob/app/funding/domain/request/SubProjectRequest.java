package org.cardanofoundation.lob.app.funding.domain.request;

import jakarta.annotation.Nullable;

import lombok.*;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SubProjectRequest {

    @Nullable
    @Schema(example = "WP-1", description = "User-defined sub-project ID. Used to look up an existing sub-project by (parentUid, subProjectId). When not found, a new sub-project is created.")
    private String subProjectId;

    @Nullable
    @Schema(example = "Work Package 1", description = "Sub-project display title — required when creating a new sub-project.")
    private String projectTitle;

}
