package org.cardanofoundation.lob.app.organisation.domain.view;


import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;


@Getter
@Builder
@AllArgsConstructor
public class ChartOfAccountView {

    private String customerCode;

    private String refCode;

    private String eventRefCode;

    private String name;

    private Long subType;

    private Long type;

    private String currency;

    private String counterParty;

    private Boolean active;

    private String parentCustomerCode;

    private OpeningBalance openingBalance;

    private Optional<Problem> error;

    public static ChartOfAccountView createSuccess(ChartOfAccount chartOfAccount) {

        if (chartOfAccount.getSubType() != null) {
            return ChartOfAccountView.builder()
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
                    .parentCustomerCode(chartOfAccount.getParentCustomerCode())
                    .error(Optional.empty())
                    .build();
        }

        return ChartOfAccountView.builder()
                .customerCode(chartOfAccount.getId().getCustomerCode())
                .eventRefCode(chartOfAccount.getEventRefCode())
                .refCode(chartOfAccount.getRefCode())
                .name(chartOfAccount.getName())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .counterParty(chartOfAccount.getCounterParty())
                .currency(chartOfAccount.getCurrencyId())
                .active(chartOfAccount.getActive())
                .parentCustomerCode(chartOfAccount.getParentCustomerCode())
                .openingBalance(chartOfAccount.getOpeningBalance())
                .build();
    }

    public static ChartOfAccountView createFail(Problem error, String customerCode) {
        return ChartOfAccountView.builder()
                .customerCode(customerCode)
                //.name(error.getTitle())
                //.subType(chartOfAccount.getSubType().getId())
                //.type(chartOfAccount.getSubType().getType().getId())
                .error(Optional.of(error))
                .build();
    }

}
