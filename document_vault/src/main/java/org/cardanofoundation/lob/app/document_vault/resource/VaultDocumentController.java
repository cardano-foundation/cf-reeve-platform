package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
}
