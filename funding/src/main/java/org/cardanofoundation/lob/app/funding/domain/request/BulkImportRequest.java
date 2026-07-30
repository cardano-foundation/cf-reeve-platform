package org.cardanofoundation.lob.app.funding.domain.request;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class BulkImportRequest extends BaseRequest {

    @Schema(description = "One CSV file per entity type (Projects, Milestones, Events). Type is auto-detected from headers.")
    @Builder.Default
    private List<MultipartFile> files = new ArrayList<>();

    @Schema(description = "When true, validates all files and reports what would be created without persisting anything.")
    private boolean dryRun;

}
