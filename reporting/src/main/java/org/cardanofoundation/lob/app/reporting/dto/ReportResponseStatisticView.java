package org.cardanofoundation.lob.app.reporting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@Builder
public class ReportResponseStatisticView {
    private Long publish;
    private Long pending;
    private Long published;
    private Long total;
}
