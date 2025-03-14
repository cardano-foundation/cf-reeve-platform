package org.cardanofoundation.lob.app.organisation.domain.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ReferenceCodeUpdate {

    @Schema(example = "0000")
    private String referenceCode;
    @Schema(example = "Example reference code")
    private String name;

    public ReferenceCode toEntity(String orgId) {
        return ReferenceCode.builder()
                .id(new ReferenceCode.Id(orgId, referenceCode))
                .name(name)
                .build();
    }
}
