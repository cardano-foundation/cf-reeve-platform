package org.cardanofoundation.lob.app.document_vault.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.service.VaultKeyService;

@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Keys", description = "Encryption-key directory / addressbook: registration, bindings, org recipients")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class VaultKeyController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final VaultKeyService keyService;

    @Operation(description = "Register a new X25519 public key for the current account")
    @PostMapping(value = "/keys", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> registerKey(@Valid @RequestBody RegisterKeyRequest request) {
        return Responses.respond(keyService.registerKey(request), HttpStatus.CREATED);
    }

    @Operation(description = "List the current account's keys across organisations (paged)")
    @GetMapping(value = "/keys/me", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<PagedResponse<VaultKeyView>> listMyKeys(
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return ResponseEntity.ok(keyService.listMyKeys(pageable));
    }

    @Operation(description = "Addressbook of an organisation the caller belongs to: recipients with keys and contact e-mail (paged)")
    @GetMapping(value = "/organisations/{organisationId}/recipients", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> listRecipients(@PathVariable String organisationId,
                                                 @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return Responses.respond(keyService.listRecipients(organisationId, pageable), HttpStatus.OK);
    }
}
