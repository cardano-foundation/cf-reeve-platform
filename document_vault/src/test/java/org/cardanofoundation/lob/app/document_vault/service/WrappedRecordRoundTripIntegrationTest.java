package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class WrappedRecordRoundTripIntegrationTest {

    @Autowired
    private WrappedRecordService service;

    @Autowired
    private EntityManager em;

    @Test
    void blobRoundTripsByteIdenticalThroughTheFullStack() {
        // adversarial blob: JSON-ish with unicode, base64 of random bytes, embedded quotes/backslashes
        byte[] random = new byte[512];
        new Random(42).nextBytes(random);
        String blob = "{\"v\":1,\"label\":\"emoji 🎉 snowman ☃\",\"wrapped\":\""
                + Base64.getEncoder().encodeToString(random) + "\",\"tricky\":\"a\\\\b\\\"c\"}";

        UpsertWrappedRecordRequest request = new UpsertWrappedRecordRequest();
        request.setRecord(blob);
        request.setVersion(1);
        service.upsert("cred-rt", request);

        // force a genuine DB round-trip: without this, get() below would return the
        // same in-memory instance from the first-level cache instead of hitting PostgreSQL
        em.flush();
        em.clear();

        String reloaded = service.get("cred-rt").get().record();
        assertEquals(blob, reloaded);
        assertEquals(new String(blob.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), reloaded);
    }
}
