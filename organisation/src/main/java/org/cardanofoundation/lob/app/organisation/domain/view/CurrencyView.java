package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.zalando.problem.Problem;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CurrencyView {

    private String customerCode;
    private String currencyId;

    private Optional<Problem> error;

    public static CurrencyView createFail(Problem error, String customerCode) {
        return new CurrencyView(customerCode, null, Optional.of(error));
    }

    public static CurrencyView createSuccess(String customerCode, String currencyId) {
        return new CurrencyView(customerCode, currencyId, Optional.empty());
    }

}
