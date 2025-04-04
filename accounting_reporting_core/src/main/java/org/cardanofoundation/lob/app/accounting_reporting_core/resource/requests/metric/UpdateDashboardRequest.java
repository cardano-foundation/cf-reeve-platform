package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.metric;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.metric.DashboardView;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UpdateDashboardRequest extends BaseRequest {

    DashboardView dashboard;
}
