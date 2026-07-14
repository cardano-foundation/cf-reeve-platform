package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

class DocumentIpfsSerialiserTest {

    private final DocumentIpfsSerialiser serialiser = new DocumentIpfsSerialiser(new ObjectMapper());

    static DocumentEntity fixture() {
        DocumentEntity entity = new DocumentEntity();
        entity.setId("doc-1");
        entity.setOrganisationId("org-hash-1");
        entity.setEnvelopeVersion(1);
        entity.setContentHash("a".repeat(64));
        entity.setPlaintextHash("b".repeat(64));
        entity.setPayloadNonce("c".repeat(24));
        entity.setCiphertextBase64("Y2lwaGVydGV4dA==");
        entity.setSlots(List.of(
                new DocumentEntity.Slot("d".repeat(64), "e".repeat(96)),
                new DocumentEntity.Slot("f".repeat(64), "0".repeat(96))));
        return entity;
    }

    @Test
    void producesTheNormativeEnvelopeDocument() throws Exception {
        String json = serialiser.serialise(fixture());

        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(1, root.get("version").asInt());
        assertEquals("REEVE_ENCRYPTED_DOCUMENT", root.get("type").asText());
        assertEquals("org-hash-1", root.get("org_id").asText());
        assertEquals("a".repeat(64), root.get("content_hash").asText());
        assertEquals("b".repeat(64), root.get("plaintext_hash").asText());
        assertEquals("Y2lwaGVydGV4dA==", root.get("payload").get("ciphertext").asText());
        assertEquals("c".repeat(24), root.get("payload").get("nonce").asText());
        assertEquals(2, root.get("slots").size());
        // slots carry ONLY crypto material — no identifiers of any kind (blueprint I6, spec B5 #3)
        root.get("slots").forEach(slot -> {
            List<String> fields = new ArrayList<>();
            slot.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("ephemeral_pub", "wrapped_dek"), fields);
        });
        assertFalse(json.toLowerCase().contains("mail"));
        assertFalse(json.toLowerCase().contains("recipient"));
        assertFalse(json.toLowerCase().contains("file"));
    }
}
