package org.cardanofoundation.lob.app.accounting_reporting_core.resource.response;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FilterOptions;

@Builder
@Getter
@Setter
@AllArgsConstructor
public class FilterOptionsResponse {

    Map<FilterOptions, List<String>> filterOptions;
    Problem error;
}
