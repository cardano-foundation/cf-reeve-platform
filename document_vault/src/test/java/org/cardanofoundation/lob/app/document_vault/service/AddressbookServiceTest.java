package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.request.CreateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpdateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.AddressbookEntryView;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddressbookServiceTest {

    private static final String X25519_PUB = "a".repeat(64);

    @Mock
    private AddressbookEntryRepository entryRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    private AddressbookService service;

    @BeforeEach
    void setUp() {
        service = new AddressbookService(entryRepository, securityHelper, organisationPublicApi);
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(entryRepository.findByOrganisationIdAndPublicKey(any(), any())).thenReturn(Optional.empty());
        when(entryRepository.save(any(AddressbookEntryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CreateAddressbookEntryRequest createRequest() {
        CreateAddressbookEntryRequest request = new CreateAddressbookEntryRequest();
        request.setOrganisationId("org1");
        request.setDisplayName("Bob Miller");
        request.setEmail("bob@example.org");
        request.setDescription("external auditor");
        request.setPublicKey(X25519_PUB);
        return request;
    }

    private AddressbookEntryEntity existing(String id) {
        AddressbookEntryEntity entry = new AddressbookEntryEntity();
        entry.setId(id);
        entry.setOrganisationId("org1");
        entry.setDisplayName("Bob Miller");
        entry.setEmail("bob@example.org");
        entry.setPublicKey(X25519_PUB);
        entry.setAssurance(KeyAssurance.PORTABLE);
        entry.setHomeOrganisationId("Privat");
        return entry;
    }

    @Test
    void addingAContactByHandPersistsThem() {
        Either<ProblemDetail, AddressbookEntryView> result = service.create(createRequest());

        assertTrue(result.isRight());
        ArgumentCaptor<AddressbookEntryEntity> saved = ArgumentCaptor.forClass(AddressbookEntryEntity.class);
        verify(entryRepository).save(saved.capture());
        assertEquals("Bob Miller", saved.getValue().getDisplayName());
        assertEquals("org1", saved.getValue().getOrganisationId());
        assertEquals(X25519_PUB, saved.getValue().getPublicKey());
    }

    /**
     * Nobody typing a key into a form can attest to how its private half was born, so a hand-entered
     * contact claims no tier. Null means unknown, and the picker must say so rather than defaulting to a
     * claim nobody made.
     */
    @Test
    void aHandEnteredContactClaimsNoAssurance() {
        service.create(createRequest());

        ArgumentCaptor<AddressbookEntryEntity> saved = ArgumentCaptor.forClass(AddressbookEntryEntity.class);
        verify(entryRepository).save(saved.capture());
        assertNull(saved.getValue().getAssurance());
    }

    /**
     * (organisation, publicKey) is unique. Import treats that collision as a refresh — "make sure this is
     * in here" — but someone typing a key in means "this is new", and being told it is not is the useful
     * answer rather than silently mutating a contact they did not know existed.
     */
    @Test
    void addingAKeyTheAddressbookAlreadyHoldsIsAConflict() {
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(existing("entry1")));

        Either<ProblemDetail, AddressbookEntryView> result = service.create(createRequest());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.DUPLICATE_PUBLIC_KEY, result.getLeft().getTitle());
        verify(entryRepository, never()).save(any());
    }

    @Test
    void editingAContactChangesOnlyWhatAHumanWroteAboutThem() {
        when(entryRepository.findById("entry1")).thenReturn(Optional.of(existing("entry1")));
        UpdateAddressbookEntryRequest request = new UpdateAddressbookEntryRequest();
        request.setDisplayName("Bob Miller (Acme)");
        request.setEmail("bob@acme.example");
        request.setDescription("auditor, 2026 engagement");

        Either<ProblemDetail, AddressbookEntryView> result = service.update("entry1", request);

        assertTrue(result.isRight());
        ArgumentCaptor<AddressbookEntryEntity> saved = ArgumentCaptor.forClass(AddressbookEntryEntity.class);
        verify(entryRepository).save(saved.capture());
        assertEquals("Bob Miller (Acme)", saved.getValue().getDisplayName());
        assertEquals("bob@acme.example", saved.getValue().getEmail());
        // the key IS the entry: an edit must never repoint a contact a sender already verified
        assertEquals(X25519_PUB, saved.getValue().getPublicKey());
        // provenance is a record of what a card claimed, not a field to tidy
        assertEquals(KeyAssurance.PORTABLE, saved.getValue().getAssurance());
        assertEquals("Privat", saved.getValue().getHomeOrganisationId());
    }

    @Test
    void deletingAContactRemovesThem() {
        AddressbookEntryEntity entry = existing("entry1");
        when(entryRepository.findById("entry1")).thenReturn(Optional.of(entry));

        Optional<ProblemDetail> problem = service.delete("entry1");

        assertTrue(problem.isEmpty());
        verify(entryRepository).delete(entry);
    }

    @Test
    void deletingAMissingContactIsNotFound() {
        when(entryRepository.findById("nope")).thenReturn(Optional.empty());

        Optional<ProblemDetail> problem = service.delete("nope");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.ADDRESSBOOK_ENTRY_NOT_FOUND, problem.get().getTitle());
        verify(entryRepository, never()).delete(any());
    }

    /**
     * The entry decides its own organisation. A caller outside it is refused even though they hold a
     * valid entry id — the id is a handle, never an authorisation.
     */
    @Test
    void aContactInAnotherOrganisationCannotBeTouched() {
        AddressbookEntryEntity foreign = existing("entry1");
        foreign.setOrganisationId("org2");
        when(entryRepository.findById("entry1")).thenReturn(Optional.of(foreign));
        when(securityHelper.canUserAccessOrg("org2")).thenReturn(false);

        Optional<ProblemDetail> problem = service.delete("entry1");

        assertTrue(problem.isPresent());
        assertEquals(403, problem.get().getStatus());
        verify(entryRepository, never()).delete(any());
    }

    @Test
    void creatingInAForeignOrganisationIsForbidden() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Either<ProblemDetail, AddressbookEntryView> result = service.create(createRequest());

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
        verify(entryRepository, never()).save(any());
    }
}
