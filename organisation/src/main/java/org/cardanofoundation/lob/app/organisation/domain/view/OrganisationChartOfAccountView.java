package org.cardanofoundation.lob.app.organisation.domain.view;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;


@Getter
@Builder
@AllArgsConstructor
public class OrganisationChartOfAccountView {

    private String customerCode;

    private String refCode;

    private String eventRefCode;

    private String name;

    public static OrganisationChartOfAccountView build(OrganisationChartOfAccount chartOfAccount) {
        return OrganisationChartOfAccountView.builder()
                .customerCode(chartOfAccount.getId().getCustomerCode())
                .eventRefCode(chartOfAccount.getEventRefCode())
                .refCode(chartOfAccount.getRefCode())
                .name(chartOfAccount.getName())
                .build();
    }

}
