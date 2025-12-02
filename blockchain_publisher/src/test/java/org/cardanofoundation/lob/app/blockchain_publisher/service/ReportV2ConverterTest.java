package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reportsV2.ReportV2Entity;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@ExtendWith(MockitoExtension.class)
class ReportV2ConverterTest {

    @Mock
    private BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    @InjectMocks
    private ReportV2Converter reportV2Converter;

    @Test
    void convertToDbDetached() {
        PublishReportEvent event = mock(PublishReportEvent.class);
        when(event.getId()).thenReturn("id123");
        when(event.getOrganisationId()).thenReturn("org123");
        when(event.getReportTemplateType()).thenReturn(ReportTemplateType.BALANCE_SHEET);
        when(event.getReportTemplateVer()).thenReturn(1L);
        when(event.getReportVer()).thenReturn(1L);
        when(event.getIntervalType()).thenReturn(null);
        when(event.getPeriod()).thenReturn((short)1);
        when(event.getYear()).thenReturn((short)2023);
        when(event.getDataMode()).thenReturn(null);
        when(event.getReportData()).thenReturn(null);
        when(event.getDispatchStatus()).thenReturn(LedgerDispatchStatus.DISPATCHED);

        ReportV2Entity reportV2Entity = reportV2Converter.convertToDbDetached(event);

        assertEquals("id123", reportV2Entity.getId());
        assertEquals("org123", reportV2Entity.getOrganisationId());
        assertEquals(ReportTemplateType.BALANCE_SHEET, reportV2Entity.getReportTemplateType());
        assertEquals(1L, reportV2Entity.getReportTemplateVer());
        assertEquals(1L, reportV2Entity.getReportVer());
        assertEquals((short)1, reportV2Entity.getPeriod());
        assertEquals((short)2023, reportV2Entity.getYear());

    }

}
