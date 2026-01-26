package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.util.HashMap;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;

@Getter
@Setter
@AllArgsConstructor
@Deprecated
public class ReportingParametersView {

    Set<ReportType> reportType;

    @JsonProperty("currencyType")
    HashMap<String,String> organisationCurrency;

    String periodFrom;
}
