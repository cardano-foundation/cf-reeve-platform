package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.cardanofoundation.lob.app.organisation.domain.request.AccountingRegimeUpdate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountingRegimeView {

    private String code;
    private String label;
    private boolean active;

    private Optional<ProblemDetail> error;

    public static AccountingRegimeView createFail(ProblemDetail error, AccountingRegimeUpdate accountingRegimeUpdate) {
        return new AccountingRegimeView(accountingRegimeUpdate.getCode(), accountingRegimeUpdate.getLabel(), Optional.ofNullable(accountingRegimeUpdate.getActive()).orElse(false), Optional.of(error));
    }

    public static AccountingRegimeView createSuccess(String code, String label, boolean active) {
        return new AccountingRegimeView(code, label, active, Optional.empty());
    }

}
