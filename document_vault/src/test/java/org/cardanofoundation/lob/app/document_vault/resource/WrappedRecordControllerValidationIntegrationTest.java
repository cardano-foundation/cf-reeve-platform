package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;

/**
 * Proves whether {@code @PathVariable @Size(max = 512)} on {@link WrappedRecordController} is
 * actually enforced. Since Spring Framework 6.1 (this repo: Boot 3.5.8 -> Framework 6.2), Spring
 * MVC performs built-in method validation of constrained handler-method parameters without
 * requiring {@code @Validated} on the controller class — an overlong path variable should be
 * rejected with 400 before the controller method body (and thus the service) ever runs.
 */
@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WrappedRecordControllerValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void overlongCredentialIdIsRejectedWithBadRequest() throws Exception {
        String overlong = "c".repeat(513);

        mockMvc.perform(get("/api/v1/document-vault/records/{credentialId}", overlong))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validLengthUnknownCredentialIdIsNotFound() throws Exception {
        String validLength = "c".repeat(512);

        mockMvc.perform(get("/api/v1/document-vault/records/{credentialId}", validLength))
                .andExpect(status().isNotFound());
    }
}
