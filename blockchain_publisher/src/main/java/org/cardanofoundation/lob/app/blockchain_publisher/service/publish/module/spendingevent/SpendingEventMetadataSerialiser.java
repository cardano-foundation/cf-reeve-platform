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
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingItemEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

/**
 * Serialises a batch of {@link SpendingEventEntity} into the {@code EVENT_BUNDLE} (label 1447) Cardano metadata
 * record, following {@code spending_event_blockchain_transaction_metadata-schema.json}.
 *
 * <p>The produced map is the <em>content</em> of the {@code 1447} object ({@code org}/{@code metadata}/
 * {@code type}/{@code data}); the {@code 1447} wrapper itself is added by {@code AbstractL1TransactionCreator}.
 */
@Service
@RequiredArgsConstructor
public class SpendingEventMetadataSerialiser {

    public static final String VERSION = "1.0";

    private final Clock clock;

    public MetadataMap serialiseToMetadataMap(String organisationId,
                                              Set<SpendingEventEntity> events,
                                              long creationSlot) {
        val globalMetadataMap = MetadataBuilder.createMap();

        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));

        // The schema requires a single top-level "org" and forbids a per-event "org" (grantEvent additionalProperties=false).
        // The dispatcher batches per organisation, so every event in the bundle shares the same organisation.
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
        if (event.getEventDate() != null) {
            metadataMap.put("date", event.getEventDate().toString());
        }

        metadataMap.put("allocation", serialiseAllocation(event));

        metadataMap.put("amount", BigDecimals.normaliseString(event.getTotalAmount()));
        metadataMap.put("currency", serialiseCurrency(event.getCurrencyId(), event.getCurrency()));

        // reporting currency (RCY) used as the "to" side of every spend item's fx_rate
        val reportingCurrencyId = event.getOrganisation().getCurrencyId();

        val milestoneMetadataList = MetadataBuilder.createList();
        for (val allocation : event.getMilestoneAllocations()) {
            milestoneMetadataList.add(serialise(allocation));
        }
        if (milestoneMetadataList.size() > 0) {
            metadataMap.put("milestone", milestoneMetadataList);
        }

        val itemsMetadataList = MetadataBuilder.createList();
        for (val item : event.getSpendingItems()) {
            itemsMetadataList.add(serialise(item, reportingCurrencyId));
        }
        if (itemsMetadataList.size() > 0) {
            metadataMap.put("items", itemsMetadataList);
        }

        return metadataMap;
    }

    private static MetadataMap serialiseAllocation(SpendingEventEntity event) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("funding_id", event.getFundingId());
        metadataMap.put("activity_id", event.getActivityId());

        if (event.getActivityTitle() != null) {
            metadataMap.put("activity_title", event.getActivityTitle());
        }
        if (event.getMilestoneId() != null) {
            metadataMap.put("milestone_id", event.getMilestoneId());
        }
        if (event.getRoundId() != null) {
            metadataMap.put("round_id", event.getRoundId());
        }
        if (event.getFundingTx() != null) {
            metadataMap.put("funding_tx", event.getFundingTx());
        }
        if (event.getFundingDocHash() != null) {
            metadataMap.put("funding_doc_hash", event.getFundingDocHash());
        }

        return metadataMap;
    }

    private static MetadataMap serialise(SpendingItemEntity item, String reportingCurrencyId) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("id", item.getItemId());
        metadataMap.put("category", item.getCategory());
        metadataMap.put("vendor", item.getVendor());
        metadataMap.put("amount", BigDecimals.normaliseString(item.getAmountFcy()));
        metadataMap.put("currency", serialiseCurrency(item.getCurrencyId(), item.getCurrency()));

        if (item.getCurrencyId() != null && reportingCurrencyId != null && item.getFxRate() != null) {
            // schema fxRate format: "<from>:<to>=<rate>", e.g. ISO_4217:USD:ISO_4217:EUR=0.9200
            metadataMap.put("fx_rate", "%s:%s=%s".formatted(item.getCurrencyId(), reportingCurrencyId, item.getFxRate().toPlainString()));
        }
        if (item.getAmountRcy() != null) {
            metadataMap.put("amount_rcy", BigDecimals.normaliseString(item.getAmountRcy()));
        }
        metadataMap.put("date", item.getSpendDate().toString());
        if (item.getDocumentHash() != null) {
            val documentMap = MetadataBuilder.createMap();
            documentMap.put("hash", item.getDocumentHash());
            metadataMap.put("document", documentMap);
        }
        if (item.getNotes() != null) {
            metadataMap.put("notes", item.getNotes());
        }

        return metadataMap;
    }

    private static MetadataMap serialise(EventMilestoneAllocationEntity allocation) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("milestone_id", allocation.getMilestoneId());
        metadataMap.put("label", allocation.getMilestoneLabel());
        metadataMap.put("amount", BigDecimals.normaliseString(allocation.getAllocatedAmount()));
        metadataMap.put("currency", serialiseCurrency(allocation.getCurrencyId(), allocation.getCurrency()));
        metadataMap.put("date", allocation.getDueDate().toString());

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
