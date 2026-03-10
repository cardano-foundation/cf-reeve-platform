package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

class API3MetadataSerialiserTest {


    private OrganisationPublicApi organisationPublicApi;
    private API3MetadataSerialiser serialiser;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-01T10:15:30Z"), ZoneId.of("UTC"));
    private static final long CREATION_SLOT = 123456L;

    @BeforeEach
    void setUp() {
        organisationPublicApi = mock(OrganisationPublicApi.class);
        serialiser = new API3MetadataSerialiser(organisationPublicApi, FIXED_CLOCK);
    }

    @Test
    void serializeReportEntity_shouldSerializeCorrectly() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");
        reportEntity.setReportData(Map.of("Test123", 5));
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        assertThat(metadataMap.get("metadata")).isNotNull();
        assertThat(metadataMap.get("org")).isNotNull();
        assertThat(metadataMap.get("type")).isEqualTo("REPORT");
        assertThat(metadataMap.get("subType")).isEqualTo("BALANCE_SHEET");
        assertThat(metadataMap.get("interval")).isEqualTo("YEAR");
        assertThat(metadataMap.get("year")).isEqualTo("2024");
        assertThat(metadataMap.get("mode")).isEqualTo("SYSTEM");
        assertThat(metadataMap.get("ver")).isEqualTo(BigInteger.valueOf(1));
        assertThat(metadataMap.get("period")).isEqualTo(BigInteger.valueOf(1));
        assertThat(metadataMap.get("data")).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");
        assertThat(data.get("test123")).isEqualTo("5");
    }

}
