package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Published by the netsuite module after it stores and verifies a configuration.
 * Consumed by whichever organisation pod is assigned the partition, which writes the verdict
 * onto the projection.
 * <p>
 * Carries both outcomes in one round trip: {@code storeStatus} drives {@code sync_state},
 * {@code validationStatus} drives {@code netsuite_valid}. They are distinct — a configuration
 * can be stored successfully and still fail to authenticate against NetSuite.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Builder
public class NetSuiteConfigAppliedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotNull
    private String organisationId;

    private long revision;

    @NotNull
    private NetSuiteConfigStatus storeStatus;

    /** Null when the configuration could not be stored, so verification never ran. */
    private NetSuiteConfigStatus validationStatus;

    private String message;

}
