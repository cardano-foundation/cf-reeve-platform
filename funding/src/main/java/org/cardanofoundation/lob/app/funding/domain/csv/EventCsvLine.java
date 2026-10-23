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
 * {@code fundingHash} + {@code fundingEntity} + {@code currencyRcy} + {@code category} +
 * {@code vendor} + {@code hash} + {@code amountFcy} + {@code currencyFcy} + {@code amountRcy}
 * (SPENDING only) + {@code eventDate}) are grouped into one event with multiple allocations,
 * mirroring the Projects+Milestones file's project grouping. That key mirrors the "Create event"
 * UI, where those fields are entered once per event — two rows differing in any of them are
 * distinct real-world transactions, not two allocations of the same one. FUNDING/REFUND rows carry
 * no spend detail, so for them this reduces to Funding ID, Hash, Entity, Currency and Date.
 *
 * <p>{@code projectId} (plus {@code subProjectId} for a sub-project) and {@code milestoneId} must
 * reference an <em>already-existing</em> project and milestone by proId — the title columns are only
 * used in messages, never to resolve anything. This file carries only allocation data (which milestone gets how much of
 * this event), not enough data to create a project or milestone from scratch. Create/update those
 * first via the Projects+Milestones file.
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

    // --- Allocation columns (one per row) — all references must already exist ---

    @CsvBindByName(column = "Project Title")
    private String projectTitle;

    /**
     * The referenced project's permanent identifier (see {@code ProjectEntity#proId}) — mandatory on
     * every allocation row, and the only key it's resolved by.
     */
    @CsvBindByName(column = "Project ID", profiles = "optional")
    private String projectId;

    /** Informational only — a sub-project is resolved by {@link #subProjectId}, which is required when this is set. */
    @CsvBindByName(column = "Sub Project Title", profiles = "optional")
    private String subProjectTitle;

    /**
     * When set, {@link #projectId} is resolved as the root project and this names its immediate
     * sub-project (a sub-project's proId is only unique under its parent). When blank,
     * {@link #projectId} is resolved at any depth.
     */
    @CsvBindByName(column = "Sub Project ID", profiles = "optional")
    private String subProjectId;

    @CsvBindByName(column = "Milestone Title")
    private String milestoneTitle;

    /** The referenced milestone's proId — mandatory on every allocation row, the only key it's resolved by. */
    @CsvBindByName(column = "Milestone ID", profiles = "optional")
    private String milestoneId;

    @CsvBindByName(column = "Allocated Amount")
    private String allocatedAmount;
}
