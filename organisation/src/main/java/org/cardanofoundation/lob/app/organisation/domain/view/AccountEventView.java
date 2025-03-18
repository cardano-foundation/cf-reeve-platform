package org.cardanofoundation.lob.app.organisation.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;


@Getter
@Builder
@AllArgsConstructor
public class AccountEventView {

    private String organisationId;
    private String debitReferenceCode;
    private String creditReferenceCode;
    private String customerCode;
    private String description;
    private String hierarchy;
    private Boolean active;

    public static AccountEventView convertFromEntity(AccountEvent eventCode){
        return AccountEventView.builder()
                .debitReferenceCode(eventCode.getId().getDebitReferenceCode())
                .creditReferenceCode(eventCode.getId().getCreditReferenceCode())
                .customerCode(eventCode.getCustomerCode())
                .organisationId(eventCode.getId().getOrganisationId())
                .description(eventCode.getName())
                .hierarchy(eventCode.getHierarchy())
                .active(eventCode.getActive())
                .build();
    }
}
