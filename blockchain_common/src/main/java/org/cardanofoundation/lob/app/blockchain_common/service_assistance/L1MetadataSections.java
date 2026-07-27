package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

/**
 * The two CIP-1447 manifest sections that every publishable type emits with an identical shape:
 * {@code metadata} and {@code org}. Previously copy-pasted across
 * {@code DocumentMetadataSerialiser}, {@code API3MetadataSerialiser}, {@code API1MetadataSerialiser}
 * and {@code SpendingEventMetadataSerialiser}.
 *
 * <p>Primitives in, {@link MetadataMap} out — deliberately no entity types. The four serialisers'
 * signatures take {@code blockchain_publisher} entities, so hosting the serialisers themselves here
 * would require this module to depend on {@code blockchain_publisher} while
 * {@code blockchain_publisher} already depends on this one. Passing scalars keeps the dependency
 * one-way and lets these helpers live in the module every publishable-owning module already has.
 *
 * <p>{@code version} is a PARAMETER, not a constant, and must stay one: each publishable type
 * carries its own ({@code 1.0} documents, {@code 1.1} transactions, {@code 1.2} reports, {@code 1.0}
 * spending events). Hardcoding any single value here would silently change on-chain bytes for the
 * other three.
 *
 * <p>Insertion order below mirrors the original serialisers for readability, but it is NOT what
 * determines the bytes: CBOR serialisation sorts map keys canonically (shortest-encoding first), so
 * {@code org} precedes {@code metadata} on the wire regardless of {@code put} order. The guard
 * against byte drift is {@code CborCharacterizationTest}, not this ordering.
 */
public final class L1MetadataSections {

    private L1MetadataSections() {
    }

    /**
     * The {@code metadata} section: chain slot the manifest was built at, an ISO-8601 timestamp, and
     * the emitting type's schema version.
     */
    public static MetadataMap metadataSection(long creationSlot, Instant timestamp, String version) {
        MetadataMap metadataMap = MetadataBuilder.createMap();

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
        metadataMap.put("version", version);

        return metadataMap;
    }

    /**
     * The {@code org} section. Callers pass the organisation's fields rather than an entity, because
     * the two entity shapes in play differ: documents and reports resolve an organisation through
     * {@code OrganisationPublicApi}, while transactions and spending events read an embedded value
     * object straight off their own entity graph.
     */
    public static MetadataMap orgSection(String id,
                                         String name,
                                         String taxIdNumber,
                                         String currencyId,
                                         String countryCode) {
        MetadataMap orgMap = MetadataBuilder.createMap();

        orgMap.put("id", id);
        orgMap.put("name", name);
        orgMap.put("tax_id_number", taxIdNumber);
        orgMap.put("currency_id", currencyId);
        orgMap.put("country_code", countryCode);

        return orgMap;
    }
}
