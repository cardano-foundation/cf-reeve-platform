package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

/**
 * The two manifest sections every publishable type emits with an identical shape: {@code metadata}
 * and {@code org}, shared by the document, transaction, report and spending-event serialisers.
 *
 * <p>Primitives in, {@link MetadataMap} out, deliberately no entity types: the serialisers take
 * {@code blockchain_publisher} entities, and that module already depends on this one, so passing
 * scalars keeps the dependency one-way.
 *
 * <p>{@code version} must stay a parameter — each publishable type carries its own, and hardcoding
 * one value here would change on-chain bytes for the others.
 *
 * <p>Insertion order below mirrors the serialisers for readability but does not determine the bytes:
 * CBOR serialisation sorts map keys canonically. {@code CborCharacterizationTest} is what guards
 * against byte drift.
 */
public final class L1MetadataSections {

    private L1MetadataSections() {
    }

    /**
     * The {@code metadata} section: chain slot the manifest was built at, an ISO-8601 timestamp, and
     * the emitting type's schema version.
     *
     * <p>Used by every type EXCEPT document — see {@link #attestableMetadataSection}.
     */
    public static MetadataMap metadataSection(long creationSlot, Instant timestamp, String version) {
        MetadataMap metadataMap = MetadataBuilder.createMap();

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp));
        metadataMap.put("version", version);

        return metadataMap;
    }

    /**
     * The {@code metadata} section for a manifest that must be derivable BEFORE it is published:
     * schema version only.
     *
     * <p>{@code creation_slot} and {@code timestamp} are omitted on purpose. Both are decided at
     * dispatch — the slot needs a live chain-tip read, the timestamp is {@code Instant.now} at
     * serialisation — so a tier that has to commit to the manifest earlier (a wallet signing an
     * attestation over it, with no chain access) cannot reproduce them and therefore cannot commit to
     * any manifest containing them.
     *
     * <p>Nothing is lost by dropping them. The slot is still persisted on the publishable entity
     * ({@code l1_creation_slot}), and both are recoverable from the chain far more reliably than from
     * the payload: an indexer reads the containing block's own slot and time, which the publisher
     * cannot influence, whereas these two were publisher-supplied and could say anything.
     *
     * @see #metadataSection(long, Instant, String)
     */
    public static MetadataMap attestableMetadataSection(String version) {
        MetadataMap metadataMap = MetadataBuilder.createMap();

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
