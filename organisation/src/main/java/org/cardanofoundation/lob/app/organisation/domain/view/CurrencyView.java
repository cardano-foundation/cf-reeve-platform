package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.zalando.problem.Problem;

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

    private Optional<Problem> error;

    public static CurrencyView createFail(Problem error, CurrencyUpdate currencyUpdate) {
        return new CurrencyView(currencyUpdate.getCode(), currencyUpdate.getIsoCode(), currencyUpdate.getActive(),Optional.of(error));
    }

    public static CurrencyView createSuccess(String customerCode, String currencyId, boolean active) {
        return new CurrencyView(customerCode, currencyId, active, Optional.empty());
    }

}
