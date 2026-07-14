package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;

@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Documents", description = "Encrypted-envelope upload and listing; the server can never read content")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class VaultDocumentController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final VaultDocumentService documentService;

    @Operation(description = "Upload an encrypted envelope: ciphertext plus per-recipient wrapped-DEK slots")
    @PostMapping(value = "/documents", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> upload(@Valid @RequestBody UploadDocumentRequest request) {
        return Responses.respond(documentService.upload(request), HttpStatus.CREATED);
    }

    @Operation(description = "Org-wide document metadata listing: paged, sortable (createdAt, fileName, sizeBytes, status), filterable by direction/status/free text")
    @GetMapping(value = "/organisations/{organisationId}/documents", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> list(@PathVariable String organisationId,
                                       @RequestParam(required = false) DocumentDirection direction,
                                       @RequestParam(required = false) VaultDocumentStatus status,
                                       @RequestParam(required = false) @Size(max = 255) String q,
                                       @PageableDefault(size = 20, sort = "createdAt",
                                               direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return Responses.respond(documentService.list(organisationId, direction, status, q, pageable), HttpStatus.OK);
    }

    @Operation(description = "Fetch the full encrypted envelope for client-side decryption (creator or recipient only; 404 otherwise)")
    @GetMapping(value = "/documents/{documentId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> fetch(@PathVariable String documentId) {
        return Responses.respond(documentService.fetch(documentId), HttpStatus.OK);
    }

    @Operation(description = "Delete a document (creator or admin only; DRAFT only)")
    @DeleteMapping(value = "/documents/{documentId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> delete(@PathVariable String documentId) {
        return Responses.respondDelete(documentService.delete(documentId));
    }
}
