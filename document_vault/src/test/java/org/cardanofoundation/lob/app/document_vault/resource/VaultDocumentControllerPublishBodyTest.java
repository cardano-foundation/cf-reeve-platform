package org.cardanofoundation.lob.app.document_vault.resource;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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

    @Test
    void publishWithUnknownFieldInBodyIsIgnored() throws Exception {
        when(documentService.publish(eq("doc1"), isNull())).thenReturn(Either.right(view()));

        mockMvc.perform(post("/api/v1/document-vault/documents/{documentId}/publish", "doc1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"somethingElse\":\"ignored\"}"))
                .andExpect(status().isOk());

        verify(documentService).publish("doc1", null);
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
