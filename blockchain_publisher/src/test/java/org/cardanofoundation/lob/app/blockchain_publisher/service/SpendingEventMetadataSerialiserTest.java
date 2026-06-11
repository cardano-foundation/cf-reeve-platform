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

import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingItemEntity;
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

    @Test
    void testSerialiseSpendingEvent() {
        SpendingItemEntity item = SpendingItemEntity.builder()
                .itemId("item1")
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00"))
                .amountRcy(new BigDecimal("85.00"))
                .currency("USD")
                .currencyId("ISO_4217:USD")
                .fxRate(new BigDecimal("0.85"))
                .spendDate(LocalDate.of(2025, 4, 3))
                .documentHash("doc-hash-1")
                .notes("Invoice #1")
                .build();

        SpendingEventEntity event = new SpendingEventEntity();
        event.setEventId("event1");
        event.setProjectId("proj1");
        event.setEventType(EventType.SPENDING);
        event.setEventDate(LocalDate.of(2025, 4, 30));
        event.setFundingId("fund1");
        event.setActivityId("act1");
        event.setActivityTitle("Activity One");
        event.setFundingTx("ftx1");
        event.setTotalAmount(new BigDecimal("100.00"));
        event.setCurrency("USD");
        event.setCurrencyId("ISO_4217:USD");
        event.setOrganisation(organisation());
        EventMilestoneAllocationEntity milestone = EventMilestoneAllocationEntity.builder()
                .milestoneId("ms1")
                .milestoneLabel("Milestone AB")
                .expectedCost(new BigDecimal("60.00"))
                .currency("USD")
                .currencyId("ISO_4217:USD")
                .dueDate(LocalDate.of(2025, 6, 30))
                .build();

        event.setSpendingItems(List.of(item));
        event.setMilestoneAllocations(List.of(milestone));

        MetadataMap result = serialiser.serialiseToMetadataMap("org123", Set.of(event), 12345L);

        MetadataMap metadata = (MetadataMap) result.get("metadata");
        assertThat(metadata.get("creation_slot")).isEqualTo(BigInteger.valueOf(12345L));
        assertThat(metadata.get("timestamp")).isEqualTo("2023-06-01T10:15:30Z");
        assertThat(metadata.get("version")).isEqualTo(VERSION);

        assertThat(result.get("type")).isEqualTo("EVENT_BUNDLE");
        assertThat(((MetadataMap) result.get("org")).get("currency_id")).isEqualTo("ISO_4217:CHF");

        CBORMetadataList dataList = (CBORMetadataList) result.get("data");
        MetadataMap eventMap = (MetadataMap) dataList.getValueAt(0);

        assertThat(eventMap.get("id")).isEqualTo("event1");
        assertThat(eventMap.get("type")).isEqualTo("SPENDING");
        assertThat(eventMap.get("date")).isEqualTo("2025-04-30");
        // SPENDING events do not carry an event-level amount (derived from items)
        assertThat(eventMap.get("amount")).isNull();

        MetadataMap currencyMap = (MetadataMap) eventMap.get("currency");
        assertThat(currencyMap.get("id")).isEqualTo("ISO_4217:USD");
        assertThat(currencyMap.get("cust_code")).isEqualTo("USD");

        MetadataMap allocationMap = (MetadataMap) eventMap.get("allocation");
        assertThat(allocationMap.get("funding_id")).isEqualTo("fund1");
        assertThat(allocationMap.get("activity_id")).isEqualTo("act1");
        assertThat(allocationMap.get("activity_title")).isEqualTo("Activity One");
        assertThat(allocationMap.get("funding_tx")).isEqualTo("ftx1");

        CBORMetadataList itemsList = (CBORMetadataList) eventMap.get("items");
        MetadataMap itemMap = (MetadataMap) itemsList.getValueAt(0);
        assertThat(itemMap.get("id")).isEqualTo("item1");
        assertThat(itemMap.get("amount")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("100.00")));
        assertThat(itemMap.get("amount_rcy")).isEqualTo(BigDecimals.normaliseString(new BigDecimal("85.00")));
        assertThat(itemMap.get("date")).isEqualTo("2025-04-03");
        assertThat(itemMap.get("fx_rate")).isEqualTo("ISO_4217:USD:ISO_4217:CHF=0.85");
        assertThat(((MetadataMap) itemMap.get("currency")).get("id")).isEqualTo("ISO_4217:USD");
        assertThat(((MetadataMap) itemMap.get("document")).get("hash")).isEqualTo("doc-hash-1");
        assertThat(itemMap.get("notes")).isEqualTo("Invoice #1");

        // SPENDING targets a single milestone object carrying only the milestone_id
        MetadataMap milestoneMap = (MetadataMap) eventMap.get("milestone");
        assertThat(milestoneMap.get("milestone_id")).isEqualTo("ms1");
        assertThat(milestoneMap.get("milestone_label")).isNull();
        assertThat(milestoneMap.get("expected_cost")).isNull();
        assertThat(milestoneMap.get("allocated_amount")).isNull();
        assertThat(milestoneMap.get("due_date")).isNull();
    }

    @Test
    void testSerialiseFundingEventWithMilestones() {
        EventMilestoneAllocationEntity allocation = EventMilestoneAllocationEntity.builder()
                .milestoneId("ms1")
                .milestoneLabel("Milestone AB")
                .allocatedAmount(new BigDecimal("50.00"))
                .currency("USD")
                .currencyId("ISO_4217:USD")
                .dueDate(LocalDate.of(2025, 6, 30))
                .build();

        SpendingEventEntity event = new SpendingEventEntity();
        event.setEventId("event2");
        event.setProjectId("proj1");
        event.setEventType(EventType.FUNDING);
        event.setEventDate(LocalDate.of(2025, 1, 15));
        event.setFundingId("fund1");
        event.setActivityId("act1");
        event.setActivityTitle("Activity One");
        event.setFundingTx("ftx1");
        event.setTotalAmount(new BigDecimal("50.00"));
        event.setCurrency("USD");
        event.setCurrencyId("ISO_4217:USD");
        event.setOrganisation(organisation());
        event.setSpendingItems(List.of());
        event.setMilestoneAllocations(List.of(allocation));

        MetadataMap result = serialiser.serialiseToMetadataMap("org123", Set.of(event), 12345L);

        CBORMetadataList dataList = (CBORMetadataList) result.get("data");
        MetadataMap eventMap = (MetadataMap) dataList.getValueAt(0);

        assertThat(eventMap.get("type")).isEqualTo("FUNDING");
        assertThat(eventMap.get("items")).isNull();

        CBORMetadataList milestoneList = (CBORMetadataList) eventMap.get("milestone");
        MetadataMap milestoneMap = (MetadataMap) milestoneList.getValueAt(0);
        assertThat(milestoneMap.get("milestone_id")).isEqualTo("ms1");
        assertThat(milestoneMap.get("milestone_label")).isNull();
        assertThat(milestoneMap.get("allocated_amount")).isNull();
        assertThat(milestoneMap.get("due_date")).isNull();
        assertThat(milestoneMap.get("currency")).isNull();

        // FUNDING/REFUND allocation does not require a milestone_id on the allocation block
        MetadataMap allocationMap = (MetadataMap) eventMap.get("allocation");
        assertThat(allocationMap.get("activity_title")).isEqualTo("Activity One");
        assertThat(allocationMap.get("funding_tx")).isEqualTo("ftx1");
    }

}
