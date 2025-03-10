package org.cardanofoundation.lob.app.organisation.domain.request;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ChartOfAccountUpdate {

    @Schema(example = "2203560100")
    private String customerCode;

    @Schema(example = "0000")
    private String eventRefCode;

    @Schema(example = "description")
    private String name;

    @Nullable
    private String subType;
}
