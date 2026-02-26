package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;


@Getter
@Builder
@AllArgsConstructor
public class AccountEventView {

    private String organisationId;
    private String debitReferenceCode;
    private String creditReferenceCode;
    private String customerCode;
    private String description;
    private Boolean active;

    private Optional<ProblemDetail> error;


    public static AccountEventView convertFromEntity(AccountEvent eventCode){
        return AccountEventView.builder()
                .debitReferenceCode(eventCode.getId().getDebitReferenceCode())
                .creditReferenceCode(eventCode.getId().getCreditReferenceCode())
                .customerCode(eventCode.getCustomerCode())
                .organisationId(eventCode.getId().getOrganisationId())
                .description(eventCode.getName())
                .active(eventCode.getActive())
                .error(Optional.empty())
                .build();
    }

    public static AccountEventView createFail(ProblemDetail error, EventCodeUpdate eventCodeUpdate) {
        return AccountEventView.builder()
                .debitReferenceCode(eventCodeUpdate.getDebitReferenceCode())
                .creditReferenceCode(eventCodeUpdate.getCreditReferenceCode())
                .description(eventCodeUpdate.getName())
                .error(Optional.of(error))
                .build();
    }
}
