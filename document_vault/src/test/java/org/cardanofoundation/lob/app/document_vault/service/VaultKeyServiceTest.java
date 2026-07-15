package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class VaultKeyServiceTest {

    private static final String HEX64 = "a".repeat(64);

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    private VaultKeyService service;

    @BeforeEach
    void currentUser() {
        service = new VaultKeyService(keyRepository, securityHelper, organisationPublicApi);
        ReflectionTestUtils.setField(service, "adminRoleName", "admin");
        // lenient: MockitoExtension defaults to STRICT_STUBS and early-return tests never consume this stub
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("acc1");
    }

    @AfterEach
    void clearSecurityContext() {
        // hasAdminRole() reads SecurityContextHolder directly; a test that populates it must not
        // let that authentication leak into the next test in this class (or another class).
        SecurityContextHolder.clearContext();
    }

    private RegisterKeyRequest request(String publicKey, String org) {
        RegisterKeyRequest request = new RegisterKeyRequest();
        request.setOrganisationId(org);
        request.setLabel("laptop");
        request.setPublicKey(publicKey);
        request.setEmail("alice@example.org");
        return request;
    }

    @Test
    void registerKeyHappyPath() {
        when(securityHelper.getCurrentUser()).thenReturn("Alice");
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64)).thenReturn(false);
        when(keyRepository.save(any(VaultKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, VaultKeyView> result = service.registerKey(request(HEX64, "org1"));

        assertTrue(result.isRight());
        assertEquals(HEX64, result.get().publicKey());
        assertEquals("alice@example.org", result.get().email());
        assertEquals("org1", result.get().organisationId());
        assertEquals("acc1", result.get().accountId());
    }

    @Test
    void registerKeyRejectsForeignOrganisation() {
        when(securityHelper.canUserAccessOrg("other-org")).thenReturn(false);

        Either<ProblemDetail, VaultKeyView> result = service.registerKey(request(HEX64, "other-org"));

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
    }

    @Test
    void registerKeyRejectsDuplicatePublicKeyWithinTheSameOrg() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64)).thenReturn(true);

        Either<ProblemDetail, VaultKeyView> result = service.registerKey(request(HEX64, "org1"));

        assertTrue(result.isLeft());
        assertEquals(409, result.getLeft().getStatus());
    }

    @Test
    void samePublicKeyIsAllowedInAnotherOrg() {
        when(securityHelper.canUserAccessOrg("org2")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org2")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org2", HEX64)).thenReturn(false);
        when(keyRepository.save(any(VaultKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.registerKey(request(HEX64, "org2")).isRight());
    }

    @Test
    void listRecipientsRequiresMembership() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertTrue(service.listRecipients("org1", Pageable.unpaged()).isLeft());
    }

    @Test
    void listRecipientsExposesPagedAddressbookEntries() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(keyRepository.findByOrganisationId("org1")).thenReturn(List.of(orgKey("k1", "acc2")));

        var result = service.listRecipients("org1", Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().total());
        assertEquals("bob@example.org", result.get().content().get(0).email());
    }

    @Test
    void deleteOwnKeySucceeds() {
        VaultKeyEntity key = orgKey("key1", "acc1"); // same as securityHelper.getCurrentUserId()
        when(keyRepository.findById("key1")).thenReturn(Optional.of(key));

        Optional<ProblemDetail> problem = service.delete("key1");

        assertTrue(problem.isEmpty());
        verify(keyRepository).delete(key);
    }

    @Test
    void deleteOthersKeyWithoutAdminIsRejected() {
        VaultKeyEntity key = orgKey("key1", "acc2"); // not the current user
        when(keyRepository.findById("key1")).thenReturn(Optional.of(key));

        Optional<ProblemDetail> problem = service.delete("key1");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.NOT_KEY_OWNER, problem.get().getTitle());
        verify(keyRepository, never()).delete(any(VaultKeyEntity.class));
    }

    /**
     * The admin bypass in {@code hasAdminRole()}: a Keycloak-authenticated caller holding
     * {@code ROLE_<keycloak.roles.admin>} (default "admin") may delete a key they do not own.
     * Mirrors VaultDocumentServiceTest's deleteByNonCreatorWithAdminRoleSucceeds.
     */
    @Test
    void deleteOthersKeyWithAdminSucceeds() {
        TestingAuthenticationToken adminAuth = new TestingAuthenticationToken("admin-user", null, "ROLE_admin");
        adminAuth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(adminAuth);

        VaultKeyEntity key = orgKey("key1", "acc2"); // not the current user ("acc1")
        when(keyRepository.findById("key1")).thenReturn(Optional.of(key));

        Optional<ProblemDetail> problem = service.delete("key1");

        assertTrue(problem.isEmpty());
        verify(keyRepository).delete(key);
    }

    @Test
    void deleteMissingKeyIsNotFound() {
        when(keyRepository.findById("nope")).thenReturn(Optional.empty());

        Optional<ProblemDetail> problem = service.delete("nope");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.KEY_NOT_FOUND, problem.get().getTitle());
    }

    @Test
    void listOrganisationKeysReturnsMappedUsers() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        Pageable pageable = PageRequest.of(0, 20);
        when(keyRepository.findByOrganisationId("org1", pageable))
                .thenReturn(new PageImpl<>(List.of(orgKey("k1", "acc2"), orgKey("k2", "acc3"))));

        Either<ProblemDetail, PagedResponse<VaultKeyView>> result =
                service.listOrganisationKeys("org1", pageable);

        assertTrue(result.isRight());
        assertEquals(2, result.get().total());
        assertEquals("acc2", result.get().content().get(0).accountId());
        assertEquals("Bob", result.get().content().get(0).accountName());
    }

    @Test
    void listOrganisationKeysForbiddenWhenNotMember() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Either<ProblemDetail, PagedResponse<VaultKeyView>> result =
                service.listOrganisationKeys("org1", PageRequest.of(0, 20));

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
    }

    private VaultKeyEntity orgKey(String keyId, String accountId) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(keyId);
        key.setAccountId(accountId);
        key.setOrganisationId("org1");
        key.setAccountName("Bob");
        key.setEmail("bob@example.org");
        key.setPublicKey(HEX64);
        key.setLabel("phone");
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }
}
