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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.WrappedRecordView;
import org.cardanofoundation.lob.app.document_vault.service.WrappedRecordService;

@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Wrapped Records", description = "Opaque wrapped-key record store for multi-device sync")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class WrappedRecordController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final WrappedRecordService recordService;

    @Operation(description = "Create or replace the wrapped record for one of the caller's passkey credentials")
    @PutMapping(value = "/records/{credentialId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> upsert(@PathVariable @Size(max = 512) String credentialId,
                                         @Valid @RequestBody UpsertWrappedRecordRequest request) {
        return Responses.respond(recordService.upsert(credentialId, request), HttpStatus.OK);
    }

    @Operation(description = "Fetch the caller's wrapped record for a credential (keychain-load on a new device)")
    @GetMapping(value = "/records/{credentialId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> get(@PathVariable @Size(max = 512) String credentialId) {
        return Responses.respond(recordService.get(credentialId), HttpStatus.OK);
    }

    @Operation(description = "List all wrapped records of the caller (paged)")
    @GetMapping(value = "/records", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<PagedResponse<WrappedRecordView>> listMine(
            @PageableDefault(size = Integer.MAX_VALUE) Pageable pageable) {
        return ResponseEntity.ok(recordService.listMine(pageable));
    }
}
