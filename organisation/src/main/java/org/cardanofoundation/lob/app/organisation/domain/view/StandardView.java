package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.cardanofoundation.lob.app.organisation.domain.request.StandardUpdate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardView {

    private String code;
    private String label;
    private boolean active;

    private Optional<ProblemDetail> error;

    public static StandardView createFail(ProblemDetail error, StandardUpdate standardUpdate) {
        return new StandardView(standardUpdate.getCode(), standardUpdate.getLabel(), Optional.ofNullable(standardUpdate.getActive()).orElse(false), Optional.of(error));
    }

    public static StandardView createSuccess(String code, String label, boolean active) {
        return new StandardView(code, label, active, Optional.empty());
    }

}
