package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecipientResolutionServiceTest {

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private KeyCardVerifier cardVerifier;

    @InjectMocks
    private RecipientResolutionService service;

    @BeforeEach
    void setUp() {
        // lenient: STRICT_STUBS would fail early-return tests that never consume these
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("sender");
        lenient().when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        // Default: every issuer is trusted. Mockito would otherwise return false for this boolean and
        // silently filter out EVERY key — the tests would fail for a reason that has nothing to do
        // with what they are testing. The de-trust tests override this per issuer id.
        lenient().when(cardVerifier.isTrustedIssuer(any())).thenReturn(true);
    }

    private VaultKeyEntity key(String id, String accountId, String publicKey) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId(accountId);
        key.setOrganisationId("org1");
        key.setAccountName("Name " + accountId);
        key.setEmail(accountId + "@example.org");
        key.setPublicKey(publicKey);
        key.setLabel("k");
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    private ResolveRecipientsRequest request(List<String> recipients) {
        ResolveRecipientsRequest request = new ResolveRecipientsRequest();
        request.setOrganisationId("org1");
        request.setRecipientAccountIds(recipients);
        return request;
    }

    @Test
    void resolvesRecipientsAddsSenderAndDedupes() {
        // recipient appears twice in the request; sender auto-added
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s", "sender", "b".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result =
                service.resolve(request(List.of("recipient", "recipient")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
        assertTrue(result.get().stream().anyMatch(v -> v.accountId().equals("sender")));
    }

    @Test
    void failsWhenRecipientHasNoKey() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(key("k-s", "sender", "b".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("keyless")));

        assertTrue(result.isLeft());
        assertEquals(422, result.getLeft().getStatus());
        assertTrue(result.getLeft().getDetail().contains("keyless"));
    }

    @Test
    void failsWhenSenderHasNoKey() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(key("k-r", "recipient", "a".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SENDER_KEY_MISSING, result.getLeft().getTitle());
    }

    @Test
    void failsForForeignOrganisation() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertTrue(service.resolve(request(List.of("recipient"))).isLeft());
    }

    @Test
    void dedupesByPublicKey() {
        // same public key registered under two key rows -> one wrap target
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r1", "recipient", "a".repeat(64)),
                        key("k-r2", "recipient", "a".repeat(64)),
                        key("k-s", "sender", "b".repeat(64))));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size());
    }

    /** "Choose a key to encrypt with": the sender narrows the self-slots to the device they picked. */
    @Test
    void senderKeyIdsNarrowTheSendersOwnSlots() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s1", "sender", "b".repeat(64)),
                        key("k-s2", "sender", "c".repeat(64))));

        ResolveRecipientsRequest request = request(List.of("recipient"));
        request.setSenderKeyIds(List.of("k-s1"));

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request);

        assertTrue(result.isRight());
        assertEquals(2, result.get().size()); // the recipient + only the chosen sender key
        assertTrue(result.get().stream().noneMatch(view -> view.keyId().equals("k-s2")));
    }

    @Test
    void emptySenderKeyIdsMeansAllOwnKeys() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s1", "sender", "b".repeat(64)),
                        key("k-s2", "sender", "c".repeat(64))));

        ResolveRecipientsRequest request = request(List.of("recipient"));
        request.setSenderKeyIds(List.of());

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request);

        assertTrue(result.isRight());
        assertEquals(3, result.get().size()); // a write-only document is not a feature
    }

    /**
     * The containment test (contract §2.8.5). This is the whole answer to "a compromised issuer can
     * seed a hostile key": drop the issuer from the config and the key it vouched for stops being a
     * wrap target — so it never gets a slot in another document. Without this filter, resolve's
     * include-all-of-a-recipient's-keys behaviour would hand the attacker a slot in every future
     * document addressed to their victim.
     */
    @Test
    void keysFromADeTrustedIssuerAreNotWrapTargets() {
        VaultKeyEntity honest = key("k-r", "recipient", "a".repeat(64));
        VaultKeyEntity hostile = key("k-evil", "recipient", "d".repeat(64));
        hostile.setOrigin(KeyOrigin.INDEXER_ISSUED);
        hostile.setAssurance(KeyAssurance.PORTABLE);
        hostile.setIssuerId("compromised-issuer");
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(honest, hostile, key("k-s", "sender", "b".repeat(64))));
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isRight());
        assertEquals(2, result.get().size()); // the honest recipient key + the sender's own
        assertTrue(result.get().stream().noneMatch(view -> view.keyId().equals("k-evil")),
                "a key vouched for by a de-trusted issuer must never become a wrap target");
    }

    /** If de-trusting leaves a recipient with no usable key, say so — never drop them silently. */
    @Test
    void aRecipientLeftWithOnlyDeTrustedKeysIsReportedMissing() {
        VaultKeyEntity onlyKey = key("k-evil", "recipient", "d".repeat(64));
        onlyKey.setOrigin(KeyOrigin.INDEXER_ISSUED);
        onlyKey.setIssuerId("compromised-issuer");
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(onlyKey, key("k-s", "sender", "b".repeat(64))));
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request(List.of("recipient")));

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.RECIPIENT_KEY_MISSING, result.getLeft().getTitle());
    }

    @Test
    void senderKeyIdsRejectsAKeyThatIsNotTheCallers() {
        when(keyRepository.findByAccountIdInAndOrganisationId(anyCollection(), eq("org1")))
                .thenReturn(List.of(
                        key("k-r", "recipient", "a".repeat(64)),
                        key("k-s1", "sender", "b".repeat(64))));

        ResolveRecipientsRequest request = request(List.of("recipient"));
        request.setSenderKeyIds(List.of("k-r")); // the recipient's key, not mine

        Either<ProblemDetail, List<RecipientKeyView>> result = service.resolve(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SENDER_KEY_INVALID, result.getLeft().getTitle());
    }
}
