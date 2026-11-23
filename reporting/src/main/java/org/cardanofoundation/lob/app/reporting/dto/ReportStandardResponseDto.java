package org.cardanofoundation.lob.app.reporting.dto;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.annotation.JsonInclude;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportStandardResponseDto {

    private String code;
    private String label;
    private boolean active;

    private Optional<ProblemDetail> error;

    public static ReportStandardResponseDto createFail(ProblemDetail error, ReportStandardDto reportStandardUpdate) {
        return new ReportStandardResponseDto(reportStandardUpdate.getCode(), reportStandardUpdate.getLabel(), Optional.ofNullable(reportStandardUpdate.getActive()).orElse(false), Optional.of(error));
    }

    public static ReportStandardResponseDto createSuccess(String code, String label, boolean active) {
        return new ReportStandardResponseDto(code, label, active, Optional.empty());
    }

}
