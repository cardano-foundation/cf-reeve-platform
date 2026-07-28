package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Serialises the encrypted envelope into the IPFS document (spec: "IPFS envelope document").
 * PII-free by construction: slots carry only ephemeral_pub + wrapped_dek; no e-mails, labels,
 * key ids, file names, or account ids exist in this format (spec B5 #3).
 *
 * <p>Lives in {@code blockchain_common} (moved from {@code blockchain_publisher}, WS3 step 1) so both
 * {@code blockchain_publisher} and {@code document_vault} can call it without either module depending
 * on the other. Takes the tier-neutral {@link DocumentPublishCommand} rather than
 * {@code blockchain_publisher}'s persisted {@code DocumentEntity} for the same reason - not annotated
 * {@code @Service}: this module registers every bean explicitly in {@code BlockchainCommonConfig}
 * rather than component-scanning, matching {@code Cip170MetadataFactory}'s precedent.
 */
@RequiredArgsConstructor
public class DocumentIpfsSerialiser {

    public static final int VERSION = 1;
    public static final String TYPE = "REEVE_ENCRYPTED_DOCUMENT";

    private final ObjectMapper objectMapper;

    public String serialise(DocumentPublishCommand command) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", VERSION);
        root.put("type", TYPE);
        root.put("org_id", command.organisationId());
        root.put("content_hash", command.contentHash());
        root.put("plaintext_hash", command.plaintextHash());

        ObjectNode payload = root.putObject("payload");
        payload.put("ciphertext", command.ciphertextBase64());
        payload.put("nonce", command.payloadNonce());

        ArrayNode slots = root.putArray("slots");
        command.slots().forEach(slot -> {
            ObjectNode slotNode = slots.addObject();
            slotNode.put("ephemeral_pub", slot.ephemeralPub());
            slotNode.put("wrapped_dek", slot.wrappedDek());
        });
        return root.toString();
    }

}
