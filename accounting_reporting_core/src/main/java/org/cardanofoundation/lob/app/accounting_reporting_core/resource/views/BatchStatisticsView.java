package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class BatchStatisticsView {

    private String batchId;

    @Nullable
    private Integer invalid;

    @Nullable
    private Integer pending;

    @Nullable
    private Integer approve;

    @Nullable
    private Integer publish;

    @Nullable
    private Integer published;

    @Nullable
    private Integer total;
}
