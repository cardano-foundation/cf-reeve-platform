package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Objects;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;

@Getter
@Builder
@AllArgsConstructor
public class ReferenceCodeView {

    String referenceCode;
    String description;
    ReferenceCodeView parent;
    String parentReferenceCode;
    boolean isActive;

    private Optional<Problem> error;

    public static ReferenceCodeView fromEntity(ReferenceCode referenceCode) {
        ReferenceCodeViewBuilder builder = ReferenceCodeView.builder()
                .referenceCode(Objects.requireNonNull(referenceCode.getId()).getReferenceCode())
                .description(referenceCode.getName())
                .isActive(referenceCode.isActive())
                .error(Optional.empty());
        if(referenceCode.getParent().isPresent()) {
            builder.parent(ReferenceCodeView.fromEntity(referenceCode.getParent().get()));
        }
        return builder.build();
    }

    public static ReferenceCodeView createFail(Problem error, ReferenceCodeUpdate referenceCodeUpdate) {
        return ReferenceCodeView.builder()
                .referenceCode(referenceCodeUpdate.getReferenceCode())
                .description(referenceCodeUpdate.getName())
                .parentReferenceCode(referenceCodeUpdate.getParentReferenceCode())
                .isActive(referenceCodeUpdate.getActive())
                .error(Optional.of(error))
                .build();
    }
}
