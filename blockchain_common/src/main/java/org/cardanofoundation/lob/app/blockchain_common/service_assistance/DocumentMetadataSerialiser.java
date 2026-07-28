package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Serialises a {@link DocumentPublishCommand} into the 1447 DOCUMENT L1 manifest (spec: "Publishing - flow and
 * formats"). The {@code org}/{@code metadata} sections use {@link L1MetadataSections} so the on-chain shape
 * stays byte-compatible across publishable types.
 *
 * <p>PII-free by construction: the {@code data} section carries only id / ipfs_cid / content_hash /
 * plaintext_hash / envelope_version / slot_count - nothing else (spec B5 #3).
 *
 * <p>Lives in {@code blockchain_common} (moved from {@code blockchain_publisher}, WS3 step 1) so both
 * {@code blockchain_publisher} and {@code document_vault} can call it without either module depending
 * on the other. Takes the tier-neutral {@link DocumentPublishCommand} rather than
 * {@code blockchain_publisher}'s persisted {@code DocumentEntity}, and takes the organisation's
 * already-resolved fields rather than an {@code OrganisationPublicApi} lookup of its own: this module
 * must not depend on {@code organisation}, so the CALLER resolves the organisation (via whichever
 * lookup its own tier owns) and passes the five {@link L1MetadataSections#orgSection} fields straight
 * through. Not annotated {@code @Service}: this module registers every bean explicitly in
 * {@code BlockchainCommonConfig} rather than component-scanning, matching
 * {@code Cip170MetadataFactory}'s precedent.
 */
@RequiredArgsConstructor
public class DocumentMetadataSerialiser {

    public static final String VERSION = "1.0";

    private final Clock clock;

    public MetadataMap serialiseToMetadataMap(DocumentPublishCommand command,
                                              String ipfsCid,
                                              long creationSlot,
                                              String organisationId,
                                              String organisationName,
                                              String organisationTaxIdNumber,
                                              String organisationCurrencyId,
                                              String organisationCountryCode) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));
        globalMetadataMap.put("org", L1MetadataSections.orgSection(
                organisationId, organisationName, organisationTaxIdNumber, organisationCurrencyId, organisationCountryCode));

        globalMetadataMap.put("type", "DOCUMENT");

        MetadataMap data = MetadataBuilder.createMap();
        data.put("id", command.documentId());
        data.put("ipfs_cid", ipfsCid);
        data.put("content_hash", command.contentHash());
        data.put("plaintext_hash", command.plaintextHash());
        data.put("envelope_version", BigInteger.valueOf(command.envelopeVersion()));
        data.put("slot_count", BigInteger.valueOf(command.slots().size()));
        globalMetadataMap.put("data", data);

        return globalMetadataMap;
    }

    private MetadataMap createMetadataSection(long creationSlot) {
        return L1MetadataSections.metadataSection(creationSlot, Instant.now(clock), VERSION);
    }

}
