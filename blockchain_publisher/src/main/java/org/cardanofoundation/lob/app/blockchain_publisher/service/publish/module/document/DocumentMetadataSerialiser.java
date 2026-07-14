package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * Serialises a {@link DocumentEntity} into the 1447 DOCUMENT L1 manifest (spec: "Publishing - flow and
 * formats"). The {@code org}/{@code metadata} sections are copied verbatim from
 * {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3MetadataSerialiser}
 * so the on-chain shape stays byte-compatible across publishable types.
 *
 * <p>PII-free by construction: the {@code data} section carries only id / ipfs_cid / content_hash /
 * plaintext_hash / envelope_version / slot_count - nothing else (spec B5 #3).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentMetadataSerialiser {

    public static final String VERSION = "1.0";

    private final OrganisationPublicApi organisationPublicApi;
    private final Clock clock;

    public MetadataMap serialiseToMetadataMap(DocumentEntity document, String ipfsCid, long creationSlot) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));

        var organisationEntity = organisationPublicApi.findByOrganisationId(document.getOrganisationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Organisation not found for id: %s".formatted(document.getOrganisationId())));
        globalMetadataMap.put("org", serialiseOrganisation(Organisation.fromOrganisationEntity(organisationEntity)));

        globalMetadataMap.put("type", "DOCUMENT");

        MetadataMap data = MetadataBuilder.createMap();
        data.put("id", document.getId());
        data.put("ipfs_cid", ipfsCid);
        data.put("content_hash", document.getContentHash());
        data.put("plaintext_hash", document.getPlaintextHash());
        data.put("envelope_version", BigInteger.valueOf(document.getEnvelopeVersion()));
        data.put("slot_count", BigInteger.valueOf(document.getSlots().size()));
        globalMetadataMap.put("data", data);

        return globalMetadataMap;
    }

    private MetadataMap createMetadataSection(long creationSlot) {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        Instant now = Instant.now(clock);

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
        metadataMap.put("version", VERSION);

        return metadataMap;
    }

    private static MetadataMap serialiseOrganisation(Organisation organisation) {
        MetadataMap orgMap = MetadataBuilder.createMap();

        orgMap.put("id", organisation.getId());
        orgMap.put("name", organisation.getName());
        orgMap.put("tax_id_number", organisation.getTaxIdNumber());
        orgMap.put("currency_id", organisation.getCurrencyId());
        orgMap.put("country_code", organisation.getCountryCode());

        return orgMap;
    }

}
