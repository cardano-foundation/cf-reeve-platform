package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.request.CreateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpdateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.AddressbookEntryView;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * The addressbook: contacts you can encrypt to, per organisation.
 *
 * These are not accounts. An entry is a public key somebody gave you, and its {@code entryId} is the
 * only handle it has. Nothing about it is verified — the sender is expected to confirm a recipient's key
 * out-of-band before encrypting to it (trust-on-first-use), so this service stores what it is told and
 * shows it honestly rather than pretending to check it.
 *
 * Any member of the organisation may read and manage its addressbook. There is no owner to defer to: a
 * contact belongs to the organisation, not to whoever happened to add them.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AddressbookService {

    private final AddressbookEntryRepository entryRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;

    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<AddressbookEntryView>> list(String organisationId,
                                                                           Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        return Either.right(PagedResponse.of(
                entryRepository.findByOrganisationId(organisationId, pageable), AddressbookService::toView));
    }

    public Either<ProblemDetail, AddressbookEntryView> create(CreateAddressbookEntryRequest request) {
        String organisationId = request.getOrganisationId();
        Optional<ProblemDetail> denied = checkOrganisation(organisationId);
        if (denied.isPresent()) {
            return Either.left(denied.get());
        }

        // (organisation, publicKey) is unique, so adding a key the addressbook already holds is a
        // conflict rather than a duplicate. Card import treats the same collision as a refresh — a user
        // re-importing a card means "make sure this is in here", where a user typing a key in by hand
        // means "this is new", and being told it is not is the useful answer.
        if (entryRepository.findByOrganisationIdAndPublicKey(organisationId, request.getPublicKey()).isPresent()) {
            return Either.left(VaultProblems.conflict(VaultProblems.DUPLICATE_PUBLIC_KEY,
                    "This public key is already in the addressbook of organisation %s."
                            .formatted(organisationId)));
        }

        AddressbookEntryEntity entry = new AddressbookEntryEntity();
        entry.setId(UUID.randomUUID().toString());
        entry.setOrganisationId(organisationId);
        entry.setDisplayName(request.getDisplayName());
        entry.setEmail(request.getEmail());
        entry.setDescription(request.getDescription());
        entry.setPublicKey(request.getPublicKey());
        // assurance stays null: nobody typing a key into a form can attest to how it was born.
        return Either.right(toView(entryRepository.save(entry)));
    }

    public Either<ProblemDetail, AddressbookEntryView> update(String entryId,
                                                              UpdateAddressbookEntryRequest request) {
        Either<ProblemDetail, AddressbookEntryEntity> found = loadAndAuthorise(entryId);
        if (found.isLeft()) {
            return Either.left(found.getLeft());
        }
        AddressbookEntryEntity entry = found.get();

        // Only what a human wrote about the contact. publicKey is absent from the request on purpose —
        // see UpdateAddressbookEntryRequest — as are assurance and homeOrganisationId, which record what
        // a card claimed and are not ours to tidy.
        entry.setDisplayName(request.getDisplayName());
        entry.setEmail(request.getEmail());
        entry.setDescription(request.getDescription());
        return Either.right(toView(entryRepository.save(entry)));
    }

    public Optional<ProblemDetail> delete(String entryId) {
        Either<ProblemDetail, AddressbookEntryEntity> found = loadAndAuthorise(entryId);
        if (found.isLeft()) {
            return Optional.of(found.getLeft());
        }
        // Documents already wrapped to this contact stay exactly as they are: their slots are immutable
        // and carry their own wrapped DEK, and the recipient holds the private half off-device. Deleting
        // only stops the contact being offered as a future recipient. (This is now true — the slot's
        // foreign key used to make the same promise and then reject the delete.)
        entryRepository.delete(found.get());
        return Optional.empty();
    }

    /**
     * The entry decides its own organisation — the id already determines which addressbook the row is
     * in, so taking an org from the caller as well would only let the two disagree.
     */
    private Either<ProblemDetail, AddressbookEntryEntity> loadAndAuthorise(String entryId) {
        Optional<AddressbookEntryEntity> entryM = entryRepository.findById(entryId);
        if (entryM.isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ADDRESSBOOK_ENTRY_NOT_FOUND,
                    "No addressbook entry %s.".formatted(entryId)));
        }
        AddressbookEntryEntity entry = entryM.get();
        if (!securityHelper.canUserAccessOrg(entry.getOrganisationId())) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(entry.getOrganisationId())));
        }
        return Either.right(entry);
    }

    private Optional<ProblemDetail> checkOrganisation(String organisationId) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Optional.of(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Optional.of(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }
        return Optional.empty();
    }

    /** Package-private + static so CardImportService reuses the exact same mapping. */
    static AddressbookEntryView toView(AddressbookEntryEntity entry) {
        return new AddressbookEntryView(entry.getId(), entry.getOrganisationId(), entry.getDisplayName(),
                entry.getEmail(), entry.getDescription(), entry.getPublicKey(), entry.getAssurance(),
                entry.getHomeOrganisationId(), entry.getCreatedAt());
    }
}
