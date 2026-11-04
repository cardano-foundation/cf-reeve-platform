package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * Serialises the encrypted envelope into the IPFS document. PII-free by construction: a slot carries
 * only {@code ephemeral_pub} and {@code wrapped_dek}, never e-mails, labels, key ids, file names or
 * account ids.
 *
 * <p>Beans are registered explicitly in {@code BlockchainCommonConfig}, so this class is not
 * annotated {@code @Service}.
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
