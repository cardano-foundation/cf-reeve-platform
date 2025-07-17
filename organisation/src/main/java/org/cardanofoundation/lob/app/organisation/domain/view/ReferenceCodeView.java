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
    ReferenceCodeView parentReferenceCode;
    boolean isActive;

    private Optional<Problem> error;

    public static ReferenceCodeView fromEntity(ReferenceCode referenceCode) {
        ReferenceCodeViewBuilder builder = ReferenceCodeView.builder()
                .referenceCode(Objects.requireNonNull(referenceCode.getId()).getReferenceCode())
                .description(referenceCode.getName())
                .isActive(referenceCode.isActive())
                .error(Optional.empty());
        if(referenceCode.getParent().isPresent()) {
            builder.parentReferenceCode(ReferenceCodeView.fromEntity(referenceCode.getParent().get()));
        }
        return builder.build();
    }

    public static ReferenceCodeView createFail(Problem error, ReferenceCodeUpdate referenceCodeUpdate, Optional<ReferenceCodeView> parentReferenceCode) {
        ReferenceCodeViewBuilder builder = ReferenceCodeView.builder()
                .referenceCode(referenceCodeUpdate.getReferenceCode())
                .description(referenceCodeUpdate.getName())
                .isActive(referenceCodeUpdate.isActive())
                .error(Optional.of(error));
        parentReferenceCode.ifPresent(builder::parentReferenceCode);
        return builder.build();
    }
}
