package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserAccessView(
        @Schema(example = "manager") String username,
        @Schema(example = "Reeve Manager") String name,
        List<OrganisationAccessView> organisations,
        @Schema(example = "[\"organisations:create\"]") List<String> platformPermissions) {

    public record OrganisationAccessView(
            @Schema(example = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94") String id,
            @Schema(example = "Cardano Foundation") String name,
            @Schema(example = "[\"transactions_reviewer\"]") List<String> roles,
            @Schema(example = "[\"transactions\", \"reporting\"]") List<String> modules,
            @Schema(example = "[\"transactions:view\", \"reports:create\"]") List<String> permissions) {
    }

}
