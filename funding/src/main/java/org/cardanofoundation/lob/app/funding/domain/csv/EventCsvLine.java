package org.cardanofoundation.lob.app.funding.domain.csv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.opencsv.bean.CsvBindByName;

/**
 * One row of the Events bulk-import CSV — one milestone allocation per row. Event-level columns
 * (everything except the allocation columns) are repeated on every row belonging to the same event;
 * consecutive rows sharing the same event natural key ({@code fundingId} + {@code eventType} +
 * {@code fundingHash} + {@code currencyRcy}) are grouped into one event with multiple allocations,
 * mirroring the Projects file's root/sub-project grouping.
 *
 * <p>Both {@code externalProjectId} and {@code externalMilestoneId} must reference an
 * <em>already-existing</em> project and milestone — this file carries only allocation data
 * (which milestone gets how much of this event), not enough data to create a project or milestone
 * from scratch. Create/update those first via the Projects and Milestones files.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EventCsvLine {

    // --- Event-level columns (repeated per group) ---

    @CsvBindByName(column = "Event Type")
    private String eventType;

    @CsvBindByName(column = "Funding ID")
    private String fundingId;

    @CsvBindByName(column = "Funding Hash", profiles = "optional")
    private String fundingHash;

    @CsvBindByName(column = "Funding Entity", profiles = "optional")
    private String fundingEntity;

    @CsvBindByName(column = "Currency RCY")
    private String currencyRcy;

    @CsvBindByName(column = "Event Date", profiles = "optional")
    private String eventDate;

    @CsvBindByName(column = "Category", profiles = "optional")
    private String category;

    @CsvBindByName(column = "Vendor", profiles = "optional")
    private String vendor;

    @CsvBindByName(column = "Amount FCY", profiles = "optional")
    private String amountFcy;

    @CsvBindByName(column = "Currency FCY", profiles = "optional")
    private String currencyFcy;

    @CsvBindByName(column = "FX Rate", profiles = "optional")
    private String fxRate;

    @CsvBindByName(column = "Amount RCY", profiles = "optional")
    private String amountRcy;

    @CsvBindByName(column = "Hash", profiles = "optional")
    private String hash;

    @CsvBindByName(column = "Notes", profiles = "optional")
    private String notes;

    // --- Allocation columns (one per row) — both references must already exist ---

    @CsvBindByName(column = "External Project ID")
    private String externalProjectId;

    @CsvBindByName(column = "External Milestone ID")
    private String externalMilestoneId;

    @CsvBindByName(column = "Allocated Amount")
    private String allocatedAmount;
}
