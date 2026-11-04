package org.cardanofoundation.lob.app.document_vault.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.enums.RecipientKind;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CredentialAttestationView;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialVerificationService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Service
@RequiredArgsConstructor
@Transactional
public class VaultKeyService {

    private final VaultKeyRepository keyRepository;
    private final AddressbookEntryRepository entryRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    /** Absent when keri_attestation is disabled — keys then simply carry no verdict. */
    private final ObjectProvider<CredentialVerificationService> verificationServiceProvider;

    @Value("${keycloak.roles.admin:admin}")
    private String adminRoleName;

    public Either<ProblemDetail, VaultKeyView> registerKey(RegisterKeyRequest request) {
        String accountId = securityHelper.getCurrentUserId();
        String organisationId = request.getOrganisationId();

        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }
        if (keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey(accountId, organisationId,
                request.getPublicKey())) {
            return Either.left(VaultProblems.conflict(VaultProblems.DUPLICATE_PUBLIC_KEY,
                    "This public key is already registered for the current account in this organisation."));
        }

        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(UUID.randomUUID().toString());
        key.setAccountId(accountId);
        key.setOrganisationId(organisationId);
        key.setAccountName(securityHelper.getCurrentUser());
        key.setCredentialId(request.getCredentialId());
        key.setPublicKey(request.getPublicKey());
        key.setLabel(request.getLabel());
        // Self-enrollment is the passkey path by definition: the key was born on the caller's device.
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);

        return Either.right(toView(keyRepository.save(key)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<VaultKeyView> listMyKeys(Pageable pageable) {
        Page<VaultKeyEntity> page = keyRepository.findByAccountId(securityHelper.getCurrentUserId(), pageable);
        Map<String, CredentialAttestationView> verdicts = attestationsFor(page);
        return PagedResponse.of(page, key -> toView(key, verdicts.get(verdictKey(key.getOrganisationId(), key.getPublicKey()))));
    }

    /**
     * Management listing: every key registered in the organisation, each with its mapped user. Any
     * member of the organisation may read it (the caller must belong to the org). Paged in the query.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<VaultKeyView>> listOrganisationKeys(String organisationId,
                                                                                   Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        Page<VaultKeyEntity> page = keyRepository.findByOrganisationId(organisationId, pageable);
        Map<String, CredentialAttestationView> verdicts = attestationsFor(page);
        return Either.right(PagedResponse.of(page, key -> toView(key, verdicts.get(verdictKey(key.getOrganisationId(), key.getPublicKey())))));
    }

    /**
     * Everyone in the org you can encrypt to: colleagues with registered keys, and addressbook contacts.
     * One list, because the sender picks a recipient without caring which store they came from — the
     * {@code kind} tells them apart where it matters. Trust is the sender's out-of-band responsibility,
     * not a server-side gate. Directories are small, so both reads are unpaged and the merged list is
     * paged in memory.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<RecipientKeyView>> listRecipients(String organisationId,
                                                                                 Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        List<RecipientKeyView> addressable = Stream.concat(
                        keyRepository.findByOrganisationId(organisationId).stream()
                                .map(VaultKeyService::toRecipientView),
                        entryRepository.findByOrganisationId(organisationId).stream()
                                .map(VaultKeyService::toRecipientView))
                .toList();
        return Either.right(PagedResponse.ofList(addressable, pageable));
    }

    /**
     * Delete a key. The owner (the account the key belongs to) may delete their own; an admin may
     * delete any. A document already wrapped to this public key stays decryptable — its ciphertext and
     * slots are immutable and the recipient holds the private half off-device; deleting only removes
     * the directory entry, so the key stops being offered as a future recipient.
     */
    public Optional<ProblemDetail> delete(String keyId) {
        Optional<VaultKeyEntity> keyM = keyRepository.findById(keyId);
        if (keyM.isEmpty()) {
            return Optional.of(VaultProblems.notFound(VaultProblems.KEY_NOT_FOUND,
                    "No key %s.".formatted(keyId)));
        }
        VaultKeyEntity key = keyM.get();
        boolean isOwner = key.getAccountId().equals(securityHelper.getCurrentUserId());
        if (!isOwner && !hasAdminRole()) {
            return Optional.of(VaultProblems.of403NotKeyOwner());
        }
        keyRepository.delete(key);
        return Optional.empty();
    }

    private boolean hasAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + adminRoleName));
    }

    /** Shared with RecipientResolutionService and CardImportService — one mapping, one place. */
    static RecipientKeyView toRecipientView(VaultKeyEntity key) {
        // recipientId is the ACCOUNT id, not the key id: a colleague may hold several keys and a sender
        // picks the person, not the device. Contacts are the other way round — one entry, one key.
        return new RecipientKeyView(RecipientKind.ORG_KEY, key.getAccountId(), key.getId(),
                key.getAccountName(), null, key.getPublicKey(), key.getLabel(),
                key.getAssurance(), key.getOrigin(), null);
    }

    static RecipientKeyView toRecipientView(AddressbookEntryEntity entry) {
        return new RecipientKeyView(RecipientKind.ADDRESSBOOK_ENTRY, entry.getId(), entry.getId(),
                entry.getDisplayName(), entry.getEmail(), entry.getPublicKey(), entry.getDescription(),
                entry.getAssurance(), null, entry.getHomeOrganisationId());
    }

    /**
     * @return the credential verdicts keri_attestation holds for this page's cards, keyed by
     *         {@code organisationId + "/" + publicKey}.
     *
     * <p>Grouped by organisation rather than assuming the page has only one. {@code /keys/me} lists a
     * user's keys across EVERY organisation they belong to, so taking the first row's organisation for
     * the whole page would look up one organisation's verdicts and then apply them to another's rows —
     * attaching a badge earned in organisation A to a key in organisation B. One query per distinct
     * organisation on the page, which for the common single-org page is still one query.
     */
    private Map<String, CredentialAttestationView> attestationsFor(Page<VaultKeyEntity> page) {
        CredentialVerificationService verificationService = verificationServiceProvider.getIfAvailable();
        if (verificationService == null || page.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> keysByOrganisation = page.getContent().stream().collect(
                Collectors.groupingBy(VaultKeyEntity::getOrganisationId,
                        Collectors.mapping(VaultKeyEntity::getPublicKey, Collectors.toList())));
        Map<String, CredentialAttestationView> byOrgAndKey = new LinkedHashMap<>();
        keysByOrganisation.forEach((organisationId, publicKeys) ->
                verificationService.findAll(organisationId, publicKeys).forEach((publicKey, view) ->
                        byOrgAndKey.put(verdictKey(organisationId, publicKey), view)));
        return byOrgAndKey;
    }

    /** Verdicts are scoped to an organisation, so the lookup key must be too. */
    private static String verdictKey(String organisationId, String publicKey) {
        return organisationId + "/" + publicKey;
    }

    /** Package-private + static so CardImportService reuses the exact same mapping. */
    static VaultKeyView toView(VaultKeyEntity key) {
        return toView(key, null);
    }

    /** As above, with the credential verdict keri_attestation holds for this key's card, if any. */
    static VaultKeyView toView(VaultKeyEntity key, @Nullable CredentialAttestationView attestation) {
        return new VaultKeyView(key.getId(), key.getOrganisationId(), key.getAccountId(), key.getAccountName(),
                key.getLabel(), key.getPublicKey(), key.getCredentialId(),
                key.getAssurance(), key.getOrigin(), key.getCreatedAt(), attestation);
    }
}
