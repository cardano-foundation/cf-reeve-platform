package org.cardanofoundation.lob.app.reporting.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class CreateCsvTemplateRequest extends BaseRequest {

    @Schema(example = "A csv file for the report creation. E.g. a csv file")
    private MultipartFile file;

}
