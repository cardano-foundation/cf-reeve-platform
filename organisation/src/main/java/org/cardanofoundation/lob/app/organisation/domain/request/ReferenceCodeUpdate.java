package org.cardanofoundation.lob.app.organisation.domain.request;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ReferenceCodeUpdate {

    @Schema(example = "0000")
    @CsvBindByName(column = "Reference Code", required = true)
    private String referenceCode;

    @Schema(example = "Example reference code")
    @CsvBindByName(column = "Name", required = true)
    private String name;

    @Nullable
    @CsvBindByName(column = "Parent Reference Code")
    private String parentReferenceCode;

    @Schema(example = "true")
    @CsvBindByName(column = "Active", required = true)
    private boolean isActive = true;

    public ReferenceCode toEntity(String orgId) {
        return ReferenceCode.builder()
                .id(new ReferenceCode.Id(orgId, referenceCode))
                .name(name)
                .isActive(isActive)
                .parentReferenceCode(parentReferenceCode.isEmpty() ? null : parentReferenceCode)
                .build();
    }
}
