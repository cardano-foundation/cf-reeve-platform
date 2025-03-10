package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;

@Getter
@Builder
@AllArgsConstructor
public class ReferenceCodeView {

    String referenceCode;
    String description;


    public static ReferenceCodeView fromEntity(ReferenceCode referenceCode) {
        return ReferenceCodeView.builder()
                .referenceCode(Objects.requireNonNull(referenceCode.getId()).getReferenceCode())
                .description(referenceCode.getName())
                .build();
    }
}
