package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventMetadataSerialiser.VERSION;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.JsonSchemaMetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventProjectAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventMetadataSerialiser;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

class SpendingEventMetadataSerialiserTest {

    private Clock fixedClock;
    private SpendingEventMetadataSerialiser serialiser;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2023-06-01T10:15:30.00Z"), ZoneId.of("UTC"));
        serialiser = new SpendingEventMetadataSerialiser(fixedClock);
    }

    private Organisation organisation() {
        Organisation organisation = new Organisation();
        organisation.setId("org123");
        organisation.setName("Test Organisation");
        organisation.setTaxIdNumber("123456789");
        organisation.setCurrencyId("ISO_4217:CHF");
        organisation.setCountryCode("CH");
        return organisation;
    }

    /** A sub-project allocation: the milestones are published nested inside the sub_project object. */
    private EventProjectAllocationEntity spendingProjectAllocation() {
        EventMilestoneAllocationEntity milestone = EventMilestoneAllocationEntity.builder()
                .milestoneId("ms1")
                .milestoneTitle("Milestone AB")
                .allocatedAmount(new BigDecimal("85.00"))
                .build();

        return EventProjectAllocationEntity.builder()
                .projectId("ProjectID1")
                .projectTitle("ProjectTitle")
                .subProjectId("SubProjectID1")
                .subProjectTitle("SubProjectTitle")
                .milestones(List.of(milestone))
                .build();
    }

    private SpendingEventEntity spendingEvent() {
        SpendingEventEntity event = new SpendingEventEntity();
        event.setEventId("event1");
        event.setEventType(EventType.SPENDING);
        event.setFundingId("fund1");
        event.setFundingTx("ftx1");
        event.setCurrency("USD");
        event.setCurrencyId("ISO_4217:USD");
        event.setEventDate(LocalDate.of(2025, 4, 3));
        // Spend detail — event level.
        event.setCategory("Personnel");
        event.setVendor("Vendor AB");
        event.setAmountFcy(new BigDecimal("100.00"));
        event.setAmountRcy(new BigDecimal("85.00"));
        event.setSpendCurrency("EUR");
        event.setSpendCurrencyId("ISO_4217:EUR");
        event.setFxRate(new BigDecimal("0.85"));
        event.setDocumentHash("doc-hash-1");
        event.setNotes("Invoice #1");
        event.setOrganisation(organisation());
        event.setProjectAllocations(List.of(spendingProjectAllocation()));
        return event;
    }

    private MetadataMap serialiseSpendingEvent() {
        return serialiser.serialiseToMetadataMap(Set.of(spendingEvent()), 12345L);
    }

    @Test
    void testSerialiseSpendingEvent_globalAndEventFields() {
        MetadataMap result = serialiseSpendingEvent();

        MetadataMap metadata = (MetadataMap) result.get("metadata");
        assertThat(metadata.get("creation_slot")).isEqualTo(BigInteger.valueOf(12345L));
        assertThat(metadata.get("timestamp")).isEqualTo("2023-06-01T10:15:30Z");
        assertThat(metadata.get("version")).isEqualTo(VERSION);

        assertThat(result.get("type")).isEqualTo("FUNDING");
        assertThat(((MetadataMap) result.get("org")).get("currency_id")).isEqualTo("ISO_4217:CHF");

        CBORMetadataList dataList = (CBORMetadataList) result.get("data");
        MetadataMap eventMap = (MetadataMap) dataList.getValueAt(0);

        assertThat(eventMap.get("id")).isEqualTo("event1");
        assertThat(eventMap.get("type")).isEqualTo("SPENDING");
        assertThat(eventMap.get("funding_tx")).isEqualTo("ftx1");
        assertThat(eventMap.get("funding_id")).isEqualTo("fund1");

        // Spend detail lives on the event now — there is no top-level "item" array.
        assertThat(eventMap.get("item")).isNull();
    }

    @Test
    void testSerialiseSpendingEvent_eventCarriesSpendDetail() {
        MetadataMap result = serialiseSpendingEvent();
        CBORMetadataList dataList = (CBORMetadataList) result.get("data");
        MetadataMap eventMap = (MetadataMap) dataList.getValueAt(0);

        // Spend detail is on the event.
        assertThat(eventMap.get("amount_rcy")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("85.00")));
        assertThat(eventMap.get("amount_fcy")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("100.00")));
        assertThat(eventMap.get("vendor")).isEqualTo("Vendor AB");
        assertThat(eventMap.get("spending_category")).isEqualTo("Personnel");
        assertThat(eventMap.get("fx_rate")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("0.85")));
        assertThat(eventMap.get("hash")).isEqualTo("doc-hash-1");
        assertThat(eventMap.get("notes")).isEqualTo("Invoice #1");
        assertThat(eventMap.get("date")).isEqualTo("2025-04-03");
        assertThat(((MetadataMap) eventMap.get("currency")).get("cust_code")).isEqualTo("EUR");

        // Sub-project allocation: project_id/project_title carry the root; the sub-project's own
        // id/title/milestones are nested under sub_project, and there are no project-level milestones.
        CBORMetadataList allocationList = (CBORMetadataList) eventMap.get("allocation");
        MetadataMap allocationMap = (MetadataMap) allocationList.getValueAt(0);
        assertThat(allocationMap.get("project_id")).isEqualTo("ProjectID1");
        assertThat(allocationMap.get("project_title")).isEqualTo("ProjectTitle");
        assertThat(allocationMap.get("milestones")).isNull();

        MetadataMap subProjectMap = (MetadataMap) allocationMap.get("sub_project");
        assertThat(subProjectMap.get("sub_project_id")).isEqualTo("SubProjectID1");
        assertThat(subProjectMap.get("sub_project_title")).isEqualTo("SubProjectTitle");

        CBORMetadataList milestoneList = (CBORMetadataList) subProjectMap.get("milestones");
        MetadataMap m = (MetadataMap) milestoneList.getValueAt(0);
        assertThat(m.get("milestone_id")).isEqualTo("ms1");
        assertThat(m.get("milestone_title")).isEqualTo("Milestone AB");
        assertThat(m.get("allocated_amount")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("85.00")));
        assertThat(m.get("amount_rcy")).isNull();
        assertThat(m.get("vendor")).isNull();
    }

    private SpendingEventEntity fundingEvent() {
        EventMilestoneAllocationEntity milestone = EventMilestoneAllocationEntity.builder()
                .milestoneId("ms1").milestoneTitle("Milestone AB").allocatedAmount(new BigDecimal("100.00"))
                .build();
        EventProjectAllocationEntity allocation = EventProjectAllocationEntity.builder()
                .projectId("ProjectID1").projectTitle("ProjectTitle")
                .milestones(List.of(milestone))
                .build();

        SpendingEventEntity event = new SpendingEventEntity();
        event.setEventId("event2");
        event.setEventType(EventType.FUNDING);
        event.setFundingId("fund1");
        event.setFundingTx("ftx1");
        event.setFundingEntity("FundingEntity");
        event.setCurrency("USD");
        event.setCurrencyId("ISO_4217:USD");
        event.setEventDate(LocalDate.of(2025, 1, 15));
        event.setOrganisation(organisation());
        event.setProjectAllocations(List.of(allocation));
        return event;
    }

    @Test
    void testSerialiseFundingEvent_milestoneHasAllocatedAmountOnly() {
        MetadataMap result = serialiser.serialiseToMetadataMap(Set.of(fundingEvent()), 12345L);
        CBORMetadataList dataList = (CBORMetadataList) result.get("data");
        MetadataMap eventMap = (MetadataMap) dataList.getValueAt(0);

        assertThat(eventMap.get("type")).isEqualTo("FUNDING");
        assertThat(eventMap.get("funding_entity")).isEqualTo("FundingEntity");
        // The event date is now serialised for every event type, not just SPENDING.
        assertThat(eventMap.get("date")).isEqualTo("2025-01-15");
        assertThat(eventMap.get("item")).isNull();

        // Direct allocation: no sub_project object, milestones at the project level.
        CBORMetadataList allocationList = (CBORMetadataList) eventMap.get("allocation");
        MetadataMap allocationMap = (MetadataMap) allocationList.getValueAt(0);
        assertThat(allocationMap.get("sub_project")).isNull();

        CBORMetadataList milestoneList = (CBORMetadataList) allocationMap.get("milestones");
        MetadataMap m = (MetadataMap) milestoneList.getValueAt(0);
        assertThat(m.get("allocated_amount")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("100.00")));
        // FUNDING milestones omit the SPENDING-only spend detail
        assertThat(m.get("amount_fcy")).isNull();
        assertThat(m.get("spending_category")).isNull();
        assertThat(m.get("vendor")).isNull();
        assertThat(m.get("fx_rate")).isNull();
        assertThat(m.get("currency")).isNull();
    }

    /**
     * End-to-end guard that the serialiser output and the JSON schema agree: serialise a mixed
     * FUNDING + SPENDING bundle exactly as the L1 creator does (CBOR → JSON) and validate it against
     * {@code spending_event_blockchain_transaction_metadata-schema.json} with the production checker.
     */
    @Test
    void serialisedBundleValidatesAgainstSchema() throws Exception {
        MetadataMap map = serialiser.serialiseToMetadataMap(Set.of(spendingEvent(), fundingEvent()), 12345L);

        byte[] bytes = CborSerializationUtil.serialize(map.getMap());
        String json = MetadataToJsonNoSchemaConverter.cborBytesToJson(bytes);

        JsonSchemaMetadataChecker checker = new JsonSchemaMetadataChecker(new ObjectMapper());
        checker.setMetadataSchemaResource(new ClassPathResource("spending_event_blockchain_transaction_metadata-schema.json"));
        checker.setEnableChecker(true);

        assertThat(checker.checkTransactionMetadata(json)).isTrue();
    }

}
