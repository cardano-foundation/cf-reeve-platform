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
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

/**
 * Serialises a batch of {@link SpendingEventEntity} into the {@code FUNDING} (label 1447) Cardano
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

        globalMetadataMap.put("type", "FUNDING");
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

        // Event date — applies to all event types (FUNDING, SPENDING, REFUND), when present.
        if (event.getEventDate() != null) {
            metadataMap.put("date", event.getEventDate().toString());
        }

        // Spend detail — SPENDING events only.
        if (event.getAmountRcy() != null) {
            metadataMap.put("amount_rcy", BigDecimals.normaliseString(event.getAmountRcy()));
        }
        if (event.getAmountFcy() != null) {
            metadataMap.put("amount_fcy", BigDecimals.normaliseString(event.getAmountFcy()));
        }
        if (event.getVendor() != null) {
            metadataMap.put("vendor", event.getVendor());
        }
        if (event.getCategory() != null) {
            metadataMap.put("spending_category", event.getCategory());
        }
        if (event.getFxRate() != null) {
            metadataMap.put("fx_rate", BigDecimals.normaliseString(event.getFxRate()));
        }
        if (event.getDocumentHash() != null) {
            metadataMap.put("hash", event.getDocumentHash());
        }
        if (event.getNotes() != null) {
            metadataMap.put("notes", event.getNotes());
        }
        if (event.getSpendCurrency() != null) {
            metadataMap.put("currency", serialiseCurrency(event.getSpendCurrencyId(), event.getSpendCurrency()));
        }

        val allocationList = MetadataBuilder.createList();
        for (val allocation : event.getProjectAllocations()) {
            allocationList.add(serialise(allocation));
        }
        metadataMap.put("allocation", allocationList);

        return metadataMap;
    }

    /**
     * An allocation is published in one of two unambiguous shapes: a direct allocation carries its
     * {@code milestones} at the project level; an allocation to a sub-project nests the sub-project's
     * own id/title/milestones under {@code sub_project} while {@code project_id}/{@code project_title}
     * keep identifying the root project.
     */
    private static MetadataMap serialise(EventProjectAllocationEntity allocation) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("project_id", allocation.getProjectId());
        if (allocation.getProjectTitle() != null) {
            metadataMap.put("project_title", allocation.getProjectTitle());
        }

        val milestoneList = MetadataBuilder.createList();
        for (val milestone : allocation.getMilestones()) {
            milestoneList.add(serialise(milestone));
        }

        if (allocation.getSubProjectId() != null) {
            val subProjectMap = MetadataBuilder.createMap();
            subProjectMap.put("sub_project_id", allocation.getSubProjectId());
            if (allocation.getSubProjectTitle() != null) {
                subProjectMap.put("sub_project_title", allocation.getSubProjectTitle());
            }
            subProjectMap.put("milestones", milestoneList);
            metadataMap.put("sub_project", subProjectMap);
        } else {
            metadataMap.put("milestones", milestoneList);
        }

        return metadataMap;
    }

    private static MetadataMap serialise(EventMilestoneAllocationEntity milestone) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("milestone_id", milestone.getMilestoneId());
        if (milestone.getMilestoneTitle() != null) {
            metadataMap.put("milestone_title", milestone.getMilestoneTitle());
        }
        if (milestone.getAllocatedAmount() != null) {
            metadataMap.put("allocated_amount", BigDecimals.normaliseString(milestone.getAllocatedAmount()));
        }

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
