package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

/**
 * Serialises the encrypted envelope into the IPFS document (spec: "IPFS envelope document").
 * PII-free by construction: slots carry only ephemeral_pub + wrapped_dek; no e-mails, labels,
 * key ids, file names, or account ids exist in this format (spec B5 #3).
 */
@Service
@RequiredArgsConstructor
public class DocumentIpfsSerialiser {

    public static final int VERSION = 1;
    public static final String TYPE = "REEVE_ENCRYPTED_DOCUMENT";

    private final ObjectMapper objectMapper;

    public String serialise(DocumentEntity document) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", VERSION);
        root.put("type", TYPE);
        root.put("org_id", document.getOrganisationId());
        root.put("content_hash", document.getContentHash());
        root.put("plaintext_hash", document.getPlaintextHash());

        ObjectNode payload = root.putObject("payload");
        payload.put("ciphertext", document.getCiphertextBase64());
        payload.put("nonce", document.getPayloadNonce());

        ArrayNode slots = root.putArray("slots");
        document.getSlots().forEach(slot -> {
            ObjectNode slotNode = slots.addObject();
            slotNode.put("ephemeral_pub", slot.getEphemeralPub());
            slotNode.put("wrapped_dek", slot.getWrappedDek());
        });
        return root.toString();
    }

}
