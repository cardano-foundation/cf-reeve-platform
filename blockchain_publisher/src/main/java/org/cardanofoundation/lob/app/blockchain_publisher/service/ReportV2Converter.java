package org.cardanofoundation.lob.app.blockchain_publisher.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reportsV2.ReportV2Entity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportV2Converter {

    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    public ReportV2Entity convertToDbDetached(PublishReportEvent event) {
        return ReportV2Entity.builder()
                .id(event.getId())
                .organisationId(event.getOrganisationId())
                .reportTemplateType(event.getReportTemplateType())
                .reportTemplateVer(event.getReportTemplateVer())
                .reportVer(event.getReportVer())
                .intervalType(event.getIntervalType())
                .period(event.getPeriod())
                .year(event.getYear())
                .dataMode(event.getDataMode())
                .reportData(event.getReportData())
                .l1SubmissionData(L1SubmissionData.builder()
                .publishStatus(blockchainPublishStatusMapper.convert(event.getDispatchStatus()))
                .build())
                .build();
    }

}
