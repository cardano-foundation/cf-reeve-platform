package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

/**
 * Communication object published by the funding module to the blockchain publisher for a single spending /
 * grant-lifecycle event. Unlike {@link SpendingEventView} (a generic read/REST projection), this object carries
 * exactly the fields required to build a schema-valid {@code EVENT_BUNDLE} ({@code grantEvent}) Cardano metadata
 * record - including the {@code allocation} block, structured {@link Currency} objects, milestones and spend items.
 */
@Getter
@Builder
@AllArgsConstructor
public class SpendingEventPublishView {

    private String eventId;
    /** Not part of the on-chain metadata; retained on the publisher entity for traceability. */
    private String projectId;
    private EventType eventType;
    private LocalDate date;

    // --- allocation (funding context) ---
    private String fundingId;
    private String activityId;
    @Nullable
    private String activityTitle;
    @Nullable
    private String milestoneId;
    @Nullable
    private String roundId;
    @Nullable
    private String fundingTx;
    @Nullable
    private String fundingDocHash;

    // --- event amount / currency ---
    private BigDecimal amount;
    private Currency currency;

    // --- FUNDING / REFUND events ---
    private List<Milestone> milestones;

    // --- SPENDING events ---
    private List<SpendItem> items;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Currency {
        /** ISO id, e.g. {@code ISO_4217:USD} or {@code ISO_24165:<token>:<dti>}. */
        private String id;
        /** Customer / short code, e.g. {@code USD}. */
        private String custCode;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Milestone {
        private String milestoneId;
        private String label;
        private BigDecimal amount;
        private Currency currency;
        private LocalDate date;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class SpendItem {
        private String itemId;
        private String category;
        private String vendor;
        private BigDecimal amountFcy;
        private Currency currency;
        private BigDecimal fxRate;
        @Nullable
        private BigDecimal amountRcy;
        private LocalDate spendDate;
        @Nullable
        private String documentHash;
        @Nullable
        private String notes;
    }

}
