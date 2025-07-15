package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CurrencyView {

    private String customerCode;
    private String currencyId;

    private Optional<Problem> error;

    public static CurrencyView createFail(Problem error, CurrencyUpdate currencyUpdate) {
        return new CurrencyView(currencyUpdate.getCustomerCode(), currencyUpdate.getCurrencyId(), Optional.of(error));
    }

    public static CurrencyView createSuccess(String customerCode, String currencyId) {
        return new CurrencyView(customerCode, currencyId, Optional.empty());
    }

}
