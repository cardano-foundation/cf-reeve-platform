package org.cardanofoundation.lob.app.reporting.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportResponseDto {

    private Long id;

    private String organisationId;

    private Long reportTemplateId;

    private String name;

    private String intervalType;

    private Short period;

    private Short year;

    private Long ver;

    private Boolean isReadyToPublish;

    private String publishError;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ReportFieldDto> fields;
}
