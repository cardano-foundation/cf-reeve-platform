package org.cardanofoundation.lob.app.document_vault.service;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Service
@RequiredArgsConstructor
@Transactional
public class VaultKeyService {

    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final KeyCardVerifier cardVerifier;

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
        key.setEmail(request.getEmail());
        key.setCredentialId(request.getCredentialId());
        key.setPublicKey(request.getPublicKey());
        key.setLabel(request.getLabel());
        // Self-enrollment is the passkey path by definition: the key was born on the caller's device.
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        key.setExternal(false);

        // self-enrolled: no issuer vouched for it, so nothing can de-trust it
        return Either.right(toView(keyRepository.save(key), true));
    }

    /**
     * Own keys are NOT filtered by issuer trust — a de-trusted key still appears, flagged
     * issuerTrusted=false. You need it to decrypt documents you already received; you simply cannot
     * encrypt anything new to it (contract §2.8.5).
     */
    @Transactional(readOnly = true)
    public PagedResponse<VaultKeyView> listMyKeys(Pageable pageable) {
        return PagedResponse.of(keyRepository.findByAccountId(securityHelper.getCurrentUserId(), pageable),
                key -> toView(key, cardVerifier.isTrustedIssuer(key.getIssuerId())));
    }

    /**
     * The addressbook withholds keys whose issuer is no longer trusted (contract §2.8.5). De-trusting
     * a compromised issuer must make every key it vouched for un-addressable immediately — that is the
     * whole containment story, and it has to happen here, where recipients are chosen.
     *
     * Filtering after paging would return short pages, so the filter is applied to the org's keys and
     * the page is built from the survivors. Directories are small (one row per key per org); if one
     * ever is not, push the predicate into the query.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<RecipientKeyView>> listRecipients(String organisationId,
                                                                                 Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        List<RecipientKeyView> addressable = keyRepository.findByOrganisationId(organisationId).stream()
                .filter(key -> cardVerifier.isTrustedIssuer(key.getIssuerId()))
                .map(VaultKeyService::toRecipientView)
                .toList();
        return Either.right(PagedResponse.ofList(addressable, pageable));
    }

    /** Shared with RecipientResolutionService and CardImportService — one mapping, one place. */
    static RecipientKeyView toRecipientView(VaultKeyEntity key) {
        return new RecipientKeyView(key.getAccountId(), key.getAccountName(), key.getEmail(),
                key.getId(), key.getPublicKey(), key.getLabel(),
                key.getAssurance(), key.getOrigin(), key.getIssuerId(), key.isExternal());
    }

    /**
     * Package-private + static so CardImportService (Task 4a) reuses the exact same mapping.
     * The trust flag is passed in rather than looked up here: the card importer has just verified
     * the issuer against the allowlist, so it knows the answer, and this keeps the mapping free of
     * dependencies.
     */
    static VaultKeyView toView(VaultKeyEntity key, boolean issuerTrusted) {
        return new VaultKeyView(key.getId(), key.getOrganisationId(), key.getLabel(), key.getPublicKey(),
                key.getEmail(), key.getCredentialId(),
                key.getAssurance(), key.getOrigin(), key.getIssuerId(), issuerTrusted,
                key.isExternal(), key.getCreatedAt());
    }
}
