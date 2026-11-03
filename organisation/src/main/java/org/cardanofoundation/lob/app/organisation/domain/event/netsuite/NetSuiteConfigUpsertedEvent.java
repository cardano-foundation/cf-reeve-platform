package org.cardanofoundation.lob.app.organisation.domain.event.netsuite;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

/**
 * Published by the organisation module after an admin create/update commits.
 * Consumed by the netsuite module, which owns the configuration.
 * <p>
 * A null {@code privateKeyEncrypted} means "reuse the key already stored for this
 * organisation" — the organisation module keeps no copy of it.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Builder
public class NetSuiteConfigUpsertedEvent {

    public static final String VERSION = "1.0";

    @NotNull
    private EventMetadata metadata;

    @NotNull
    private String organisationId;

    private long revision;

    @NotNull
    private String baseUrl;

    @NotNull
    private String tokenUrl;

    @NotNull
    private String clientId;

    @NotNull
    private String certificateId;

    /**
     * Nullable {@code v1:}-prefixed envelope. Excluded from {@link #toString()} because the
     * Kafka bridge in cf-reeve-application logs whole events at INFO.
     */
    @ToString.Exclude
    private String privateKeyEncrypted;

}
