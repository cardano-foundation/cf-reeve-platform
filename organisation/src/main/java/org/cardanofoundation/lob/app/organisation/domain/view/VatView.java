package org.cardanofoundation.lob.app.organisation.domain.view;

import java.math.BigDecimal;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;
import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;



@Getter
@Builder
@AllArgsConstructor
public class VatView {

    private String organisationId;
    private String customerCode;
    private String rate;
    private String countryCode;
    private String description;
    private Boolean active;

    private Problem error;

    public Optional<Problem> getError() {
        return Optional.ofNullable(error);
    }


    public static VatView convertFromEntity(Vat vat) {
        return VatView.builder()
                .customerCode(vat.getId().getCustomerCode())
                .organisationId(vat.getId().getOrganisationId())
                .rate(BigDecimals.normaliseString(vat.getRate()))
                .countryCode(vat.getCountryCode())
                .description(vat.getDescription())
                .active(vat.getActive())
                .build();
    }

    public static VatView createFail(VatUpdate vatUpdate, Problem error) {
        return VatView.builder()
                .customerCode(vatUpdate.getCustomerCode())
                .rate(Optional.ofNullable(vatUpdate.getRate()).map(BigDecimal::toPlainString).orElse(null))
                .countryCode(vatUpdate.getCountryCode())
                .description(vatUpdate.getDescription())
                .active(vatUpdate.getActive())
                .error(error)
                .build();
    }
}
