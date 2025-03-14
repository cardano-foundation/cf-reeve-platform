package org.cardanofoundation.lob.app.organisation.domain.request;

import static java.lang.Boolean.TRUE;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EventCodeUpdate {

    @Schema(example = "0000")
    private String debitReferenceCode;

    @Schema(example = "1111")
    private String creditReferenceCode;

    @Schema(example = "Example reference code")
    private String name;

    @Schema(example = "Hierarchy")
    private String hierarchy;

    private Boolean active = TRUE;
}
