package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@AllArgsConstructor
public class TransactionReconciliationStatisticView {


    private Integer missingInERP = 0;

    private Integer inProcessing = 0;

    private Integer newInERP = 0;

    private Integer newVersionNotPublished = 0;

    private Integer newVersion = 0;

    private Integer newVersionPublished = 0;

    private Long OK = 0L;

    private Integer NOK = 0;

    private Long NEVER = 0L;

    private Integer TOTAL = 0;


}
