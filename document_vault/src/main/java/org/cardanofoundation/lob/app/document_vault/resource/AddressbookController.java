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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.document_vault.domain.request.CreateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpdateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.service.AddressbookService;
import org.cardanofoundation.lob.app.document_vault.service.CardImportService;

/**
 * The ADDRESSBOOK: public keys people gave you, so you can encrypt to them.
 *
 * Contacts, not accounts — nobody logs in as one, so an entry has no Keycloak identity and its entry id
 * is its only handle. Keys belonging to Keycloak users who own the private half are organisation keys and
 * live at {@link VaultKeyController} instead.
 *
 * Any member of the organisation may read and manage its addressbook. A contact belongs to the
 * organisation, not to whoever added them, so there is no owner to defer to.
 */
@RestController
@RequestMapping("/api/v1/document-vault")
@Tag(name = "Document Vault — Addressbook",
        description = "External contacts you can encrypt to. Nothing here is verified: confirm a key out-of-band before use.")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class AddressbookController {

    private static final String ALL_ROLES = "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole()) "
            + "or hasRole(@securityConfig.getAccountantRole()) or hasRole(@securityConfig.getAuditorRole())";

    private final AddressbookService addressbookService;
    private final CardImportService cardImportService;

    @Operation(description = "List an organisation's addressbook (member-only, paged). `assurance` is null "
            + "when unknown — a hand-entered contact claims no custody tier, and none can be checked.")
    @GetMapping(value = "/organisations/{organisationId}/addressbook", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> listAddressbook(@PathVariable String organisationId,
                                                  @PageableDefault(size = 20) Pageable pageable) {
        return Responses.respond(addressbookService.list(organisationId, pageable), HttpStatus.OK);
    }

    @Operation(description = "Add a contact by hand, when you have someone's public key but no key card. "
            + "Conflicts (409) if the organisation's addressbook already holds that public key.")
    @PostMapping(value = "/addressbook", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> createEntry(@Valid @RequestBody CreateAddressbookEntryRequest request) {
        return Responses.respond(addressbookService.create(request), HttpStatus.CREATED);
    }

    @Operation(description = "Correct a contact's name, e-mail or description. The public key is NOT "
            + "editable: it is what the entry means, and repointing it would silently redirect a contact "
            + "a sender already verified. Delete the entry and add the new key instead.")
    @PutMapping(value = "/addressbook/{entryId}", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> updateEntry(@PathVariable String entryId,
                                              @Valid @RequestBody UpdateAddressbookEntryRequest request) {
        return Responses.respond(addressbookService.update(entryId, request), HttpStatus.OK);
    }

    @Operation(description = "Remove a contact. Documents already encrypted to them are untouched and stay "
            + "decryptable — the contact simply stops being offered as a future recipient.")
    @DeleteMapping(value = "/addressbook/{entryId}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> deleteEntry(@PathVariable String entryId) {
        return Responses.respondDelete(addressbookService.delete(entryId));
    }

    @Operation(description = "Import a permissionless key card. A card about somebody else becomes an "
            + "addressbook contact; a card about YOU becomes one of your own organisation keys and appears "
            + "in /keys/me instead. The response's `destination` says which happened. Re-importing a card "
            + "refreshes the existing row rather than duplicating it. Nothing is verified — the card is "
            + "unsigned, so confirm the key out-of-band before encrypting to it.")
    @PostMapping(value = "/addressbook/import", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(ALL_ROLES)
    public ResponseEntity<Object> importCard(@Valid @RequestBody ImportCardRequest request) {
        return Responses.respond(cardImportService.importCard(request), HttpStatus.OK);
    }
}
