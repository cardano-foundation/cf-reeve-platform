package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CurrencyView {

    private String code;
    private String isoCode;
    private boolean active;

    private Optional<ProblemDetail> error;

    public static CurrencyView createFail(ProblemDetail error, CurrencyUpdate currencyUpdate) {
        return new CurrencyView(currencyUpdate.getCode(), currencyUpdate.getIsoCode(), Optional.ofNullable(currencyUpdate.getActive()).orElse(false),Optional.of(error));
    }

    public static CurrencyView createSuccess(String customerCode, String currencyId, boolean active) {
        return new CurrencyView(customerCode, currencyId, active, Optional.empty());
    }

}
