package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.math.BigInteger;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Serialises a {@link DocumentPublishCommand} into the DOCUMENT L1 manifest. The {@code org} and
 * {@code metadata} sections come from {@link L1MetadataSections} so the on-chain shape stays
 * byte-compatible across publishable types.
 *
 * <p>The one deliberate divergence is {@code metadata}: this type uses
 * {@link L1MetadataSections#attestableMetadataSection}, which carries the schema version alone. A
 * document can be attested by a holder's wallet before it is published, and that wallet has neither a
 * chain tip nor the publisher's clock — so a manifest carrying {@code creation_slot} or
 * {@code timestamp} is one no wallet can commit to. The other publishable types keep both.
 *
 * <p>The {@code data} section carries nothing beyond id, ipfs_cid, content_hash, plaintext_hash,
 * envelope_version, slot_count and recipient_key_hashes. All but the last are PII-free;
 * {@code recipient_key_hashes} is a deliberate exception that trades permanent recipient linkability
 * for recipient-side filtering in the Indexer (see docs/onChainFormat.md "Recipient key hashes").
 *
 * <p>Organisation fields are passed in already resolved rather than looked up here, because this
 * module must not depend on {@code organisation}. Beans are registered explicitly in
 * {@code BlockchainCommonConfig}, so this class is not annotated {@code @Service}.
 */
public class DocumentMetadataSerialiser {

    /** 1.1 added {@code data.recipient_key_hashes}; 1.0 manifests carry none. */
    public static final String VERSION = "1.1";

    public MetadataMap serialiseToMetadataMap(DocumentPublishCommand command,
                                              String ipfsCid,
                                              String organisationId,
                                              String organisationName,
                                              String organisationTaxIdNumber,
                                              String organisationCurrencyId,
                                              String organisationCountryCode) {
        MetadataMap globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("metadata", L1MetadataSections.attestableMetadataSection(VERSION));
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
        // recipient_key_hashes[i] must line up with slots[i] in the IPFS envelope: a recipient uses
        // the index to address their own slot. Never sort or deduplicate.
        MetadataList recipientKeyHashes = MetadataBuilder.createList();
        command.slots().forEach(slot -> recipientKeyHashes.add(slot.recipientKeyHash()));
        data.put("recipient_key_hashes", recipientKeyHashes);
        globalMetadataMap.put("data", data);

        return globalMetadataMap;
    }

}
