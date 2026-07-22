package org.cardanofoundation.lob.app.document_vault.resource;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;

/**
 * MockMvc slice test for {@code publish}'s optional body (design §5.1, Task 14): the controller
 * wired against a bare Mockito mock of {@link VaultDocumentService} via
 * {@link MockMvcBuilders#standaloneSetup} — no Spring context, mirroring
 * {@code KeriAttestationControllerTest}'s precedent, since document_vault had no prior
 * controller-level slice test for this endpoint's request handling to extend.
 *
 * <p>Security ({@code @PreAuthorize}) is out of scope here — see
 * {@link VaultDocumentControllerSecurityTest} for why (method security is inert without Keycloak).
 * This test only exercises HTTP-layer body handling: absent body, present body, and {@code @Size}
 * enforcement on an oversized ceremony id.
 */
class VaultDocumentControllerPublishBodyTest {

    private VaultDocumentService documentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        documentService = mock(VaultDocumentService.class);
        VaultDocumentController controller = new VaultDocumentController(documentService);
        // MockMvcBuilders.standaloneSetup's default MappingJackson2HttpMessageConverter() builds its
        // ObjectMapper via Spring's Jackson2ObjectMapperBuilder, whose OWN defaults DISABLE
        // DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES — unlike production, which wires the
        // strict Jackson2ObjectMapperBuilder bean from support's JsonConfig (FAIL_ON_UNKNOWN_PROPERTIES
        // explicitly enabled). A plain `new ObjectMapper()` restores Jackson's own vanilla default
        // (FAIL_ON_UNKNOWN_PROPERTIES = true), matching production and letting PublishDocumentRequest's
        // `@JsonIgnoreProperties(ignoreUnknown = false)` (M3 cross-review F6 fix) actually take effect:
        // that per-class annotation only participates in Jackson's unknown-property handling, it does
        // not by itself override a mapper where the FAIL_ON_UNKNOWN_PROPERTIES feature is off.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    private static DocumentView view() {
        return new DocumentView("doc1", "q3-report.pdf", "application/pdf", null, 10L, "a".repeat(64), 1,
                VaultDocumentStatus.PUBLISHED, null, null, null, null, "Alice", null);
    }

    @Test
    void publishWithNoBodyAtAllPassesNullCeremonyId() throws Exception {
        when(documentService.publish(eq("doc1"), isNull())).thenReturn(Either.right(view()));

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1"))
                .andExpect(status().isOk());

        verify(documentService).publish("doc1", null);
    }

    @Test
    void publishWithEmptyJsonBodyPassesNullCeremonyId() throws Exception {
        when(documentService.publish(eq("doc1"), isNull())).thenReturn(Either.right(view()));

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(documentService).publish("doc1", null);
    }

    @Test
    void publishWithCeremonyIdInBodyPassesItThrough() throws Exception {
        when(documentService.publish("doc1", "cer-1")).thenReturn(Either.right(view()));

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attestationCeremonyId\":\"cer-1\"}"))
                .andExpect(status().isOk());

        verify(documentService).publish("doc1", "cer-1");
    }

    /** M3 cross-review F6 fix: {@code @JsonIgnoreProperties(ignoreUnknown = false)} means an unknown
     *  field is no longer silently dropped - it fails Jackson deserialization outright, so a typo'd
     *  {@code attestationCeremonyId} can never silently fall through to a plain, unattested publish. */
    @Test
    void publishWithUnknownFieldInBodyIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"somethingElse\":\"ignored\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(documentService);
    }

    /** M3 cross-review F6 fix: a present-but-blank ceremony id is rejected by the service (not
     *  normalized to null) - this only proves the controller propagates whatever status the service
     *  returns for it, unaffected by the request-shape change above. */
    @Test
    void publishWithBlankCeremonyIdInBodyPropagatesTheServices422() throws Exception {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "attestationCeremonyId must not be blank.");
        problem.setTitle("ATTESTATION_CEREMONY_ID_BLANK");
        when(documentService.publish("doc1", "   ")).thenReturn(Either.left(problem));

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attestationCeremonyId\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());

        verify(documentService).publish("doc1", "   ");
    }

    @Test
    void publishRejectsOverlongCeremonyIdBeforeReachingTheService() throws Exception {
        String overlong = "c".repeat(65);

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attestationCeremonyId\":\"" + overlong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishPropagatesProblemDetailFromService() throws Exception {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Attestation unavailable.");
        problem.setTitle("ATTESTATION_UNAVAILABLE");
        when(documentService.publish("doc1", "cer-1")).thenReturn(Either.left(problem));

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attestationCeremonyId\":\"cer-1\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

}
