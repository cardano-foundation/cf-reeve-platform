package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Report template response containing all template details")
public class ReportTemplateListResponseDto {


    @Schema(description = "List of report templates")
    private List<ReportTemplateResponseDto> templates;

    @Schema(description = "Total number of report templates available", example = "100")
    private long total;
    @Schema(description = "Total number of pages available", example = "10")
    private int totalPages;
    @Schema(description = "Current page number", example = "1")
    private int page;
    @Schema(description = "Number of templates per page", example = "10")
    private int size;

}
