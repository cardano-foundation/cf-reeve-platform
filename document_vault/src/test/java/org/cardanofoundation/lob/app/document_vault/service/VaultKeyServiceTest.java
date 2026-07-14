package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
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
    @Mock
    private KeyCardVerifier cardVerifier;

    @InjectMocks
    private VaultKeyService service;

    @BeforeEach
    void currentUser() {
        // lenient: MockitoExtension defaults to STRICT_STUBS and early-return tests never consume this stub
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("acc1");
        // without this, Mockito's default `false` would make every key look de-trusted and the
        // addressbook would come back empty for reasons unrelated to what these tests check
        lenient().when(cardVerifier.isTrustedIssuer(any())).thenReturn(true);
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
        when(keyRepository.findByOrganisationId("org1")).thenReturn(List.of(orgKey("k1", "acc2", null)));

        var result = service.listRecipients("org1", Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().total());
        assertEquals("bob@example.org", result.get().content().get(0).email());
    }

    /**
     * The containment property (contract §2.8.5): de-trust an issuer and every key it vouched for
     * leaves the addressbook. Nobody can pick it as a recipient again.
     */
    @Test
    void addressbookWithholdsKeysFromADeTrustedIssuer() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(keyRepository.findByOrganisationId("org1")).thenReturn(List.of(
                orgKey("k1", "acc2", null),                        // self-enrolled, always trusted
                orgKey("k-evil", "acc2", "compromised-issuer")));  // vouched for by a stolen key
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        var result = service.listRecipients("org1", Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().total());
        assertEquals("k1", result.get().content().get(0).keyId());
    }

    private VaultKeyEntity orgKey(String keyId, String accountId, String issuerId) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(keyId);
        key.setAccountId(accountId);
        key.setOrganisationId("org1");
        key.setAccountName("Bob");
        key.setEmail("bob@example.org");
        key.setPublicKey(HEX64);
        key.setLabel("phone");
        key.setIssuerId(issuerId);
        key.setOrigin(issuerId == null ? KeyOrigin.SELF_ENROLLED : KeyOrigin.INDEXER_ISSUED);
        key.setAssurance(issuerId == null ? KeyAssurance.PASSKEY : KeyAssurance.PORTABLE);
        return key;
    }
}
