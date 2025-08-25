package org.cardanofoundation.lob.app.accounting_reporting_core.resource.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;


@Builder
@Getter
@Setter
@AllArgsConstructor
public class FilteringOptionsListResponse {
    String customerCode;
    String name;
    String description;

}
