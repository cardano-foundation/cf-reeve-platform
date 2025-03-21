package org.cardanofoundation.lob.app.organisation.domain.view;


import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;


@Getter
@Builder
@AllArgsConstructor
public class OrganisationChartOfAccountView {

    private String customerCode;

    private String refCode;

    private String eventRefCode;

    private String name;

    private Long subType;

    private Long type;

    private String currency;

    private String counterParty;

    private Boolean active;

    private OpeningBalance openingBalance;

    private Optional<Problem> error;

    public static OrganisationChartOfAccountView createSuccess(OrganisationChartOfAccount chartOfAccount) {

        if (chartOfAccount.getSubType() != null) {
            return OrganisationChartOfAccountView.builder()
                    .customerCode(chartOfAccount.getId().getCustomerCode())
                    .eventRefCode(chartOfAccount.getEventRefCode())
                    .refCode(chartOfAccount.getRefCode())
                    .name(chartOfAccount.getName())
                    .subType(chartOfAccount.getSubType().getId())
                    .type(chartOfAccount.getSubType().getType().getId())
                    .currency(chartOfAccount.getCurrencyId())
                    .counterParty(chartOfAccount.getCounterParty())
                    .active(chartOfAccount.getActive())
                    .openingBalance(chartOfAccount.getOpeningBalance())
                    .error(Optional.empty())
                    .build();
        }

        return OrganisationChartOfAccountView.builder()
                .customerCode(chartOfAccount.getId().getCustomerCode())
                .eventRefCode(chartOfAccount.getEventRefCode())
                .refCode(chartOfAccount.getRefCode())
                .name(chartOfAccount.getName())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .counterParty(chartOfAccount.getCounterParty())
                .currency(chartOfAccount.getCurrencyId())
                .active(chartOfAccount.getActive())
                .openingBalance(chartOfAccount.getOpeningBalance())
                .build();
    }

    public static OrganisationChartOfAccountView createFail(Problem error) {
        return OrganisationChartOfAccountView.builder()
                //.name(error.getTitle())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .error(Optional.of(error))
                .build();
    }

}
