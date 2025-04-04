package org.cardanofoundation.lob.app.organisation.domain.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ReportTypeFieldUpdate {

    private Long reportTypeId;
    private Long reportTypeFieldId;
    private Long organisationChartOfAccountSubTypeId;

}
