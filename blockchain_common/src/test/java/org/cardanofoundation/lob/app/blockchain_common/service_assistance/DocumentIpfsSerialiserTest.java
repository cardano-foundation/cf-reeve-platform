package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

class DocumentIpfsSerialiserTest {

    private final DocumentIpfsSerialiser serialiser = new DocumentIpfsSerialiser(new ObjectMapper());

    static DocumentPublishCommand fixture() {
        return new DocumentPublishCommand(
                "org-hash-1",
                "doc-1",
                1,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(24),
                "Y2lwaGVydGV4dA==",
                List.of(
                        // The recipient key hashes are deliberately populated here: the envelope
                        // assertions below prove the serialiser drops them, so the hash reaches L1 only.
                        new DocumentPublishCommand.PublishSlot("d".repeat(64), "e".repeat(96),
                                "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae"),
                        new DocumentPublishCommand.PublishSlot("f".repeat(64), "0".repeat(96),
                                "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4")),
                null, null);
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
        // slots carry ONLY crypto material — no identifiers of any kind
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
