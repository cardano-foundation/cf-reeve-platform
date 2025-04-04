package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Objects;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;

@Getter
@Builder
@AllArgsConstructor
public class ReferenceCodeView {

    String referenceCode;
    String description;
    String parentReferenceCode;
    boolean isActive;

    private Optional<Problem> error;

    public static ReferenceCodeView fromEntity(ReferenceCode referenceCode) {
        return ReferenceCodeView.builder()
                .referenceCode(Objects.requireNonNull(referenceCode.getId()).getReferenceCode())
                .description(referenceCode.getName())
                .parentReferenceCode(referenceCode.getParentReferenceCode())
                .isActive(referenceCode.isActive())
                .error(Optional.empty())
                .build();
    }

    public static ReferenceCodeView createFail(Problem error) {
        return ReferenceCodeView.builder()
                //.name(error.getTitle())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .error(Optional.of(error))
                .build();
    }
}
