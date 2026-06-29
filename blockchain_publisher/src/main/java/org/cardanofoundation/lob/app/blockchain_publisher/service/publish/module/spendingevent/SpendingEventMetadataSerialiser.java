package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventProjectAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingItemEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

/**
 * Serialises a batch of {@link SpendingEventEntity} into the {@code EVENT_BUNDLE} (label 1447) Cardano
 * metadata record, following {@code spending_event_blockchain_transaction_metadata-schema.json}.
 *
 * <p>The produced map is the <em>content</em> of the {@code 1447} object ({@code org}/{@code metadata}/
 * {@code type}/{@code data}); the {@code 1447} wrapper itself is added by {@code AbstractL1TransactionCreator}.
 *
 * <p>Every monetary value is emitted as a decimal string (e.g. {@code "100"}, {@code "0.85"}) because
 * Cardano on-chain transaction metadata cannot encode floats/decimals — this applies to amounts and to
 * {@code fx_rate} alike.
 */
@Service
@RequiredArgsConstructor
public class SpendingEventMetadataSerialiser {

    public static final String VERSION = "1.0";

    private final Clock clock;

    public MetadataMap serialiseToMetadataMap(Set<SpendingEventEntity> events,
                                              long creationSlot) {
        val globalMetadataMap = MetadataBuilder.createMap();

        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));

        // The schema requires a single top-level "org". The dispatcher batches per organisation, so
        // every event in the bundle shares the same organisation.
        globalMetadataMap.put("org", serialise(events.stream().findFirst().orElseThrow().getOrganisation()));

        val eventList = MetadataBuilder.createList();
        events.forEach(event -> eventList.add(serialise(event)));

        globalMetadataMap.put("type", "EVENT_BUNDLE");
        globalMetadataMap.put("data", eventList);

        return globalMetadataMap;
    }

    private MetadataMap createMetadataSection(long creationSlot) {
        val metadataMap = MetadataBuilder.createMap();

        val now = Instant.now(clock);

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
        metadataMap.put("version", VERSION);

        return metadataMap;
    }

    private static MetadataMap serialise(SpendingEventEntity event) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("id", event.getEventId());
        metadataMap.put("type", event.getEventType().name());
        if (event.getFundingTx() != null) {
            metadataMap.put("funding_tx", event.getFundingTx());
        }
        metadataMap.put("funding_id", event.getFundingId());
        // funding_entity is published for FUNDING events only.
        if (event.getFundingEntity() != null) {
            metadataMap.put("funding_entity", event.getFundingEntity());
        }

        val allocationList = MetadataBuilder.createList();
        for (val allocation : event.getProjectAllocations()) {
            allocationList.add(serialise(allocation));
        }
        metadataMap.put("allocation", allocationList);

        val itemList = MetadataBuilder.createList();
        for (val item : event.getSpendingItems()) {
            itemList.add(serialise(item));
        }
        if (itemList.size() > 0) {
            metadataMap.put("item", itemList);
        }

        return metadataMap;
    }

    private static MetadataMap serialise(EventProjectAllocationEntity allocation) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("project_id", allocation.getProjectId());
        if (allocation.getProjectTitle() != null) {
            metadataMap.put("project_title", allocation.getProjectTitle());
        }
        if (allocation.getSubProjectTitle() != null) {
            metadataMap.put("sub_project_title", allocation.getSubProjectTitle());
        }

        val milestoneList = MetadataBuilder.createList();
        for (val milestone : allocation.getMilestones()) {
            milestoneList.add(serialise(milestone));
        }
        metadataMap.put("milestones", milestoneList);

        return metadataMap;
    }

    private static MetadataMap serialise(EventMilestoneAllocationEntity milestone) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("milestone_id", milestone.getMilestoneId());
        if (milestone.getMilestoneTitle() != null) {
            metadataMap.put("milestone_title", milestone.getMilestoneTitle());
        }
        if (milestone.getAmountRcy() != null) {
            metadataMap.put("amount_rcy", BigDecimals.normaliseString(milestone.getAmountRcy()));
        }

        return metadataMap;
    }

    private static MetadataMap serialise(SpendingItemEntity item) {
        val metadataMap = MetadataBuilder.createMap();

        if (item.getAmountRcy() != null) {
            metadataMap.put("amount_rcy", BigDecimals.normaliseString(item.getAmountRcy()));
        }
        if (item.getAmountFcy() != null) {
            metadataMap.put("amount_fcy", BigDecimals.normaliseString(item.getAmountFcy()));
        }
        if (item.getVendor() != null) {
            metadataMap.put("vendor", item.getVendor());
        }
        if (item.getCategory() != null) {
            metadataMap.put("spending_category", item.getCategory());
        }
        if (item.getFxRate() != null) {
            metadataMap.put("fx_rate", BigDecimals.normaliseString(item.getFxRate()));
        }
        if (item.getDocumentHash() != null) {
            metadataMap.put("hash", item.getDocumentHash());
        }
        if (item.getNotes() != null) {
            metadataMap.put("notes", item.getNotes());
        }
        metadataMap.put("date", item.getSpendDate().toString());
        metadataMap.put("currency", serialiseCurrency(item.getCurrencyId(), item.getCurrency()));

        return metadataMap;
    }

    private static MetadataMap serialiseCurrency(String currencyId, String custCode) {
        val metadataMap = MetadataBuilder.createMap();

        if (currencyId != null) {
            metadataMap.put("id", currencyId);
        }
        metadataMap.put("cust_code", custCode);

        return metadataMap;
    }

    private static MetadataMap serialise(Organisation org) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("id", org.getId());
        metadataMap.put("name", org.getName());
        metadataMap.put("tax_id_number", org.getTaxIdNumber());
        metadataMap.put("currency_id", org.getCurrencyId());
        metadataMap.put("country_code", org.getCountryCode());

        return metadataMap;
    }

}
