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
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3MetadataSerialiser;
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

    @Test
    void serializeReportEntity_withNewJsonFormatWithFieldOrder() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        Map<String, Object> reportData = Map.of(
            "Assets", Map.of(
                "_o", 0,
                "CurrentAssets", Map.of(
                    "_o", 0,
                    "Cash", Map.of("v", 1000, "_o", 0),
                    "AccountsReceivable", Map.of("v", 2000, "_o", 1)
                ),
                "FixedAssets", Map.of(
                    "_o", 1,
                    "Property", Map.of("v", 5000, "_o", 0)
                )
            ),
            "Liabilities", Map.of(
                "_o", 1,
                "CurrentLiabilities", Map.of(
                    "_o", 0,
                    "AccountsPayable", Map.of("v", 1500, "_o", 0)
                )
            )
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        assertThat(metadataMap.get("data")).isNotNull();

        MetadataMap data = (MetadataMap) metadataMap.get("data");

        // Verify Assets section with order
        MetadataMap assets = (MetadataMap) data.get("assets");
        assertThat(assets).isNotNull();
        assertThat(assets.get("_o")).isEqualTo(BigInteger.valueOf(0));

        // Verify CurrentAssets subsection with order
        MetadataMap currentAssets = (MetadataMap) assets.get("current_assets");
        assertThat(currentAssets).isNotNull();
        assertThat(currentAssets.get("_o")).isEqualTo(BigInteger.valueOf(0));

        // Verify leaf field Cash with value and order
        MetadataMap cash = (MetadataMap) currentAssets.get("cash");
        assertThat(cash).isNotNull();
        assertThat(cash.get("v")).isEqualTo("1000");
        assertThat(cash.get("_o")).isEqualTo(BigInteger.valueOf(0));

        // Verify leaf field AccountsReceivable with value and order
        MetadataMap accountsReceivable = (MetadataMap) currentAssets.get("accounts_receivable");
        assertThat(accountsReceivable).isNotNull();
        assertThat(accountsReceivable.get("v")).isEqualTo("2000");
        assertThat(accountsReceivable.get("_o")).isEqualTo(BigInteger.valueOf(1));

        // Verify FixedAssets subsection with order
        MetadataMap fixedAssets = (MetadataMap) assets.get("fixed_assets");
        assertThat(fixedAssets).isNotNull();
        assertThat(fixedAssets.get("_o")).isEqualTo(BigInteger.valueOf(1));

        // Verify Liabilities section with order
        MetadataMap liabilities = (MetadataMap) data.get("liabilities");
        assertThat(liabilities).isNotNull();
        assertThat(liabilities.get("_o")).isEqualTo(BigInteger.valueOf(1));
    }

    @Test
    void serializeReportEntity_withMixedFormats() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // Mixed format: some old format (plain values), some new format (with order)
        Map<String, Object> reportData = Map.of(
            "OldField", 100,  // Old format
            "NewField", Map.of("v", 200, "_o", 0)  // New format
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        assertThat(metadataMap.get("data")).isNotNull();

        MetadataMap data = (MetadataMap) metadataMap.get("data");

        // Verify old format field
        assertThat(data.get("old_field")).isEqualTo("100");

        // Verify new format field
        MetadataMap newField = (MetadataMap) data.get("new_field");
        assertThat(newField).isNotNull();
        assertThat(newField.get("v")).isEqualTo("200");
        assertThat(newField.get("_o")).isEqualTo(BigInteger.valueOf(0));
    }

    @Test
    void serializeReportEntity_withNestedFieldOrder() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // Deeply nested structure with field orders at each level
        Map<String, Object> reportData = Map.of(
            "Section1", Map.of(
                "_o", 0,
                "Subsection1", Map.of(
                    "_o", 0,
                    "Field1", Map.of("v", 10, "_o", 0),
                    "Field2", Map.of("v", 20, "_o", 1)
                ),
                "Subsection2", Map.of(
                    "_o", 1,
                    "Field3", Map.of("v", 30, "_o", 0)
                )
            )
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap section1 = (MetadataMap) data.get("section1");
        assertThat(section1.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap subsection1 = (MetadataMap) section1.get("subsection1");
        assertThat(subsection1.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap field1 = (MetadataMap) subsection1.get("field1");
        assertThat(field1.get("v")).isEqualTo("10");
        assertThat(field1.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap field2 = (MetadataMap) subsection1.get("field2");
        assertThat(field2.get("v")).isEqualTo("20");
        assertThat(field2.get("_o")).isEqualTo(BigInteger.valueOf(1));

        MetadataMap subsection2 = (MetadataMap) section1.get("subsection2");
        assertThat(subsection2.get("_o")).isEqualTo(BigInteger.valueOf(1));
    }

    @Test
    void serializeReportEntity_withStringValuesInNewFormat() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // New format with string values
        Map<String, Object> reportData = Map.of(
            "TextField", Map.of("v", "Some Text", "_o", 0)
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap textField = (MetadataMap) data.get("text_field");
        assertThat(textField).isNotNull();
        assertThat(textField.get("v")).isEqualTo("Some Text");
        assertThat(textField.get("_o")).isEqualTo(BigInteger.valueOf(0));
    }

    @Test
    void serializeReportEntity_withNullValuesInNewFormat() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // New format with null value - use LinkedHashMap to support null values
        Map<String, Object> nullFieldMap = new java.util.LinkedHashMap<>();
        nullFieldMap.put("v", null);
        nullFieldMap.put("_o", 0);

        Map<String, Object> reportData = new java.util.LinkedHashMap<>();
        reportData.put("NullField", nullFieldMap);

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap nullField = (MetadataMap) data.get("null_field");
        assertThat(nullField).isNotNull();
        assertThat(nullField.get("_o")).isEqualTo(BigInteger.valueOf(0));
        // Null value should not be present in the metadata
        assertThat(nullField.get("v")).isNull();
    }

    @Test
    void serializeReportEntity_withMultipleSectionsAndOrdering() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // Multiple sections with field orders
        Map<String, Object> reportData = Map.of(
            "Section1", Map.of(
                "_o", 0,
                "Field1", Map.of("v", 10, "_o", 0),
                "Field2", Map.of("v", 20, "_o", 1)
            ),
            "Section2", Map.of(
                "_o", 1,
                "Field3", Map.of("v", 30, "_o", 0)
            )
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap section1 = (MetadataMap) data.get("section1");
        assertThat(section1.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap section2 = (MetadataMap) data.get("section2");
        assertThat(section2.get("_o")).isEqualTo(BigInteger.valueOf(1));
    }

    @Test
    void serializeReportEntity_withLongValuesInNewFormat() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // New format with long values
        Map<String, Object> reportData = Map.of(
            "LongField", Map.of("v", 9999999999L, "_o", 0)
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap longField = (MetadataMap) data.get("long_field");
        assertThat(longField).isNotNull();
        assertThat(longField.get("v")).isNotNull();
        assertThat(longField.get("_o")).isEqualTo(BigInteger.valueOf(0));
    }

    @Test
    void serializeReportEntity_withDoubleValuesInNewFormat() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // New format with double values
        Map<String, Object> reportData = Map.of(
            "DoubleField", Map.of("v", 123.45, "_o", 0)
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap doubleField = (MetadataMap) data.get("double_field");
        assertThat(doubleField).isNotNull();
        assertThat(doubleField.get("v")).isNotNull();
        assertThat(doubleField.get("_o")).isEqualTo(BigInteger.valueOf(0));
    }

    @Test
    void serializeReportEntity_withCamelCaseKeyConversionAndFieldOrder() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // CamelCase field names with field order
        Map<String, Object> reportData = Map.of(
            "CurrentAssets", Map.of(
                "_o", 0,
                "AccountsReceivable", Map.of("v", 5000, "_o", 0),
                "PrepaidExpenses", Map.of("v", 1000, "_o", 1)
            )
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap currentAssets = (MetadataMap) data.get("current_assets");
        assertThat(currentAssets).isNotNull();
        assertThat(currentAssets.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap accountsReceivable = (MetadataMap) currentAssets.get("accounts_receivable");
        assertThat(accountsReceivable).isNotNull();
        assertThat(accountsReceivable.get("v")).isEqualTo("5000");
        assertThat(accountsReceivable.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap prepaidExpenses = (MetadataMap) currentAssets.get("prepaid_expenses");
        assertThat(prepaidExpenses).isNotNull();
        assertThat(prepaidExpenses.get("v")).isEqualTo("1000");
        assertThat(prepaidExpenses.get("_o")).isEqualTo(BigInteger.valueOf(1));
    }

    @Test
    void serializeReportEntity_withEmptySectionButWithOrder() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // Section with order but no leaf fields
        Map<String, Object> reportData = Map.of(
            "EmptySection", Map.of("_o", 0)
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap emptySection = (MetadataMap) data.get("empty_section");
        assertThat(emptySection).isNotNull();
        assertThat(emptySection.get("_o")).isEqualTo(BigInteger.valueOf(0));
    }

    @Test
    void serializeReportEntity_withHighFieldOrderNumbers() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // High field order numbers
        Map<String, Object> reportData = Map.of(
            "Field1", Map.of("v", 100, "_o", 99),
            "Field2", Map.of("v", 200, "_o", 255)
        );

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");

        MetadataMap field1 = (MetadataMap) data.get("field1");
        assertThat(field1.get("_o")).isEqualTo(BigInteger.valueOf(99));

        MetadataMap field2 = (MetadataMap) data.get("field2");
        assertThat(field2.get("_o")).isEqualTo(BigInteger.valueOf(255));
    }

    @Test
    void serializeReportEntity_withTwoLevelNestingAndOrdering() {
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = mock(org.cardanofoundation.lob.app.organisation.domain.entity.Organisation.class);
        when(organisationPublicApi.findByOrganisationId("org123"))
                .thenReturn(Optional.of(org));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-v2-001");
        reportEntity.setPeriod((short) 1);
        reportEntity.setOrganisationId("org123");

        // Two levels of nesting with field orders
        Map<String, Object> level2A = new java.util.LinkedHashMap<>();
        level2A.put("_o", 0);
        level2A.put("Field1", Map.of("v", 111, "_o", 0));
        level2A.put("Field2", Map.of("v", 112, "_o", 1));

        Map<String, Object> level2B = new java.util.LinkedHashMap<>();
        level2B.put("_o", 1);
        level2B.put("Field3", Map.of("v", 121, "_o", 0));

        Map<String, Object> reportData = new java.util.LinkedHashMap<>();
        reportData.put("Section1", level2A);
        reportData.put("Section2", level2B);

        reportEntity.setReportData(reportData);
        reportEntity.setYear((short) 2024);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setReportVer(1L);

        MetadataMap metadataMap = serialiser.serialiseToMetadataMap(reportEntity, CREATION_SLOT);

        assertThat(metadataMap).isNotNull();
        MetadataMap data = (MetadataMap) metadataMap.get("data");
        assertThat(data).isNotNull();

        MetadataMap section1 = (MetadataMap) data.get("section1");
        assertThat(section1).isNotNull();
        assertThat(section1.get("_o")).isEqualTo(BigInteger.valueOf(0));

        MetadataMap section2 = (MetadataMap) data.get("section2");
        assertThat(section2).isNotNull();
        assertThat(section2.get("_o")).isEqualTo(BigInteger.valueOf(1));
    }

}
