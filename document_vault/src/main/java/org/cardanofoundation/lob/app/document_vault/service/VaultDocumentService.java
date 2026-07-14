package org.cardanofoundation.lob.app.document_vault.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B3: the client delivers exactly two crypto outputs (ciphertext + slots); the server
 * assigns the ID, content-addresses, persists and indexes. Blueprint B5/I5: nothing in here may
 * decrypt, unwrap or otherwise process secret material — validation is structural only.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VaultDocumentService {

    /**
     * Blueprint I7 posture: this is a SET, not a single value. When envelope v2 ships, 2 is added
     * and 1 stays — old client versions must keep being accepted. Unknown (future) versions are
     * rejected: a server cannot store an envelope schema it does not know.
     */
    public static final Set<Integer> SUPPORTED_ENVELOPE_VERSIONS = Set.of(1);

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final ApplicationEventPublisher eventPublisher;
    /** Used at upload to reject slots wrapped to a key whose issuer has been de-trusted (§2.8.5). */
    private final KeyCardVerifier cardVerifier;

    @Value("${lob.document_vault.max-document-bytes:10485760}")
    private long maxDocumentBytes;

    @Value("${lob.document_vault.max-slots:64}")
    private int maxSlots;

    @Value("${keycloak.roles.admin:admin}")
    private String adminRoleName;

    public Either<ProblemDetail, DocumentUploadedView> upload(UploadDocumentRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        // Structural checks (no external call) go first, cheapest-first: a malformed envelope
        // should not cost an organisation lookup before it is rejected.
        if (!SUPPORTED_ENVELOPE_VERSIONS.contains(request.getEnvelopeVersion())) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.UNSUPPORTED_ENVELOPE_VERSION,
                    "Envelope version %d is not supported; supported versions: %s."
                            .formatted(request.getEnvelopeVersion(), SUPPORTED_ENVELOPE_VERSIONS)));
        }
        if (request.getSlots().size() > maxSlots) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.TOO_MANY_SLOTS,
                    "Envelope has %d slots; the maximum is %d.".formatted(request.getSlots().size(), maxSlots)));
        }

        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(request.getPayload().getCiphertext());
        } catch (IllegalArgumentException e) {
            return Either.left(VaultProblems.badRequest(VaultProblems.INVALID_PAYLOAD,
                    "ciphertext is not valid base64."));
        }
        if (ciphertext.length == 0) {
            return Either.left(VaultProblems.badRequest(VaultProblems.INVALID_PAYLOAD, "ciphertext is empty."));
        }
        if (ciphertext.length > maxDocumentBytes) {
            return Either.left(VaultProblems.payloadTooLarge(
                    "Ciphertext exceeds the maximum of %d bytes.".formatted(maxDocumentBytes)));
        }

        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }

        List<String> keyIds = request.getSlots().stream().map(UploadDocumentRequest.SlotRequest::getKeyId).toList();
        Map<String, VaultKeyEntity> keysById = keyRepository.findAllById(keyIds).stream()
                .collect(Collectors.toMap(VaultKeyEntity::getId, Function.identity()));
        for (UploadDocumentRequest.SlotRequest slot : request.getSlots()) {
            VaultKeyEntity key = keysById.get(slot.getKeyId());
            if (key == null || !key.getOrganisationId().equals(organisationId)) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s is unknown or not registered in organisation %s."
                                .formatted(slot.getKeyId(), organisationId)));
            }
            // Closes the stale-client window in the issuer containment (contract §2.8.5): a client that
            // cached the addressbook BEFORE an issuer was de-trusted would otherwise still upload a slot
            // wrapped to a key that issuer vouched for. Resolve is not an authorization gate and a
            // hostile client can put anything in a slot — but an HONEST client with stale state is the
            // likely case, and it costs one condition to stop it. Re-resolve and re-encrypt.
            if (!cardVerifier.isTrustedIssuer(key.getIssuerId())) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s was vouched for by issuer %s, which is no longer trusted. "
                                .formatted(slot.getKeyId(), key.getIssuerId())
                                + "Re-resolve the recipients and encrypt again."));
            }
        }

        VaultDocumentEntity document = new VaultDocumentEntity();
        document.setId(UUID.randomUUID().toString());
        document.setOrganisationId(organisationId);
        document.setEnvelopeVersion(request.getEnvelopeVersion());
        document.setContentHash(sha256Hex(ciphertext));
        document.setPlaintextHash(request.getPlaintextHash());
        document.setCiphertext(ciphertext);
        document.setPayloadNonce(request.getPayload().getNonce());
        document.setFileName(request.getFileName());
        document.setContentType(request.getContentType());
        document.setDescription(request.getDescription());
        document.setSizeBytes(ciphertext.length);
        document.setCreatedByAccount(securityHelper.getCurrentUserId());
        document.setCreatedByName(securityHelper.getCurrentUser());
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek()))
                .toList());

        VaultDocumentEntity saved = documentRepository.save(document);

        Set<String> recipientAccountIds = request.getSlots().stream()
                .map(slot -> keysById.get(slot.getKeyId()).getAccountId())
                .collect(Collectors.toSet());
        eventPublisher.publishEvent(new DocumentSharedEvent(saved.getId(), organisationId, recipientAccountIds));

        return Either.right(new DocumentUploadedView(saved.getId(), saved.getContentHash(), saved.getCreatedAt()));
    }

    /**
     * Org-wide listing (product decision): every org member sees ALL org documents' metadata.
     * Optional filters: direction (relative to the caller), status, q (fileName/description substring).
     * Envelope fetch stays restricted — metadata visibility does not grant ciphertext access.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, PagedResponse<DocumentView>> list(String organisationId,
                                                                   @Nullable DocumentDirection direction,
                                                                   @Nullable VaultDocumentStatus status,
                                                                   @Nullable String q,
                                                                   Pageable pageable) {
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        String accountId = securityHelper.getCurrentUserId();
        Page<VaultDocumentEntity> page = documentRepository.search(organisationId, accountId,
                direction == null ? null : direction.name(), status,
                (q == null || q.isBlank()) ? null : escapeLikeMetacharacters(q), pageable);
        return Either.right(PagedResponse.of(page, this::toView));
    }

    /**
     * Escapes LIKE metacharacters ({@code \}, {@code %}, {@code _}) in a user-supplied free-text
     * query before it is bound into {@link VaultDocumentRepository#search}. Without this a filename
     * or description containing a literal {@code %} or {@code _} would match wrongly, and a bare
     * {@code %} query would match every document in the organisation. Backslash MUST be escaped
     * first, or a user-supplied backslash would be mistaken for our own escape prefix.
     */
    private static String escapeLikeMetacharacters(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private DocumentView toView(VaultDocumentEntity document) {
        return new DocumentView(document.getId(), document.getFileName(), document.getContentType(),
                document.getDescription(), document.getSizeBytes(), document.getContentHash(),
                document.getEnvelopeVersion(), document.getStatus(), document.getLedgerDispatchStatus(),
                document.getLedgerDispatchError(), document.getTxHash(), document.getIpfsCid(),
                document.getCreatedByName(), document.getCreatedAt());
    }

    /**
     * Blueprint D2. Detail for any org member; ciphertext ONLY for the creator and recipients.
     * Decryption is strictly client-side — the backend cannot decrypt, and never tries.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, DocumentEnvelopeView> fetch(String documentId) {
        String accountId = securityHelper.getCurrentUserId();
        Optional<VaultDocumentEntity> documentM = documentRepository.findById(documentId);
        // 404 for a missing document AND for a non-member: to an outsider the two are the same thing.
        if (documentM.isEmpty() || !securityHelper.canUserAccessOrg(documentM.get().getOrganisationId())) {
            return Either.left(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s accessible to the current account.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();

        // Resolve the slot keys once: they answer both "who can read this?" and "may I?".
        List<String> keyIds = document.getSlots().stream().map(DocumentSlot::getKeyId).toList();
        Map<String, VaultKeyEntity> slotKeys = keyRepository.findAllById(keyIds).stream()
                .collect(Collectors.toMap(VaultKeyEntity::getId, key -> key));

        boolean envelopeAccessible = document.getCreatedByAccount().equals(accountId)
                || slotKeys.values().stream().anyMatch(key -> key.getAccountId().equals(accountId));

        return Either.right(new DocumentEnvelopeView(
                document.getId(), document.getOrganisationId(), document.getStatus(),
                document.getEnvelopeVersion(), document.getFileName(), document.getContentType(),
                document.getDescription(), document.getSizeBytes(), document.getContentHash(),
                document.getPlaintextHash(),
                envelopeAccessible,
                // payload AND slots are the envelope: both go to participants only. A non-participant
                // has no use for a wrappedDek and no business holding one — a draft is not public.
                envelopeAccessible
                        ? new DocumentEnvelopeView.PayloadView(
                                Base64.getEncoder().encodeToString(document.getCiphertext()),
                                document.getPayloadNonce())
                        : null,
                envelopeAccessible
                        ? document.getSlots().stream()
                                .map(slot -> new DocumentEnvelopeView.SlotView(slot.getKeyId(),
                                        slot.getRecipientRef(), slot.getEphemeralPub(), slot.getWrappedDek()))
                                .toList()
                        : null,
                // "who can read this?" — org-visible, and carries no key material whatsoever
                document.getSlots().stream()
                        .map(slot -> slotKeys.get(slot.getKeyId()))
                        .filter(Objects::nonNull)
                        .map(key -> new DocumentEnvelopeView.RecipientView(key.getId(), key.getAccountId(),
                                key.getAccountName(), key.getLabel(), key.getAssurance()))
                        .toList(),
                document.getLedgerDispatchStatus(), document.getLedgerDispatchError(),
                document.getTxHash(), document.getIpfsCid(),
                document.getCreatedByName(), document.getCreatedAt()));
    }

    public Optional<ProblemDetail> delete(String documentId) {
        Optional<VaultDocumentEntity> documentM = documentRepository.findById(documentId);
        if (documentM.isEmpty()) {
            return Optional.of(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        // membership first: Keycloak admin roles are realm-wide, so an out-of-org admin must not delete
        if (!securityHelper.canUserAccessOrg(document.getOrganisationId())) {
            return Optional.of(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(document.getOrganisationId())));
        }
        if (document.getStatus() != VaultDocumentStatus.DRAFT) {
            return Optional.of(VaultProblems.conflict(VaultProblems.DOCUMENT_PUBLISHED_IMMUTABLE,
                    "Document %s is published and can never be edited or deleted.".formatted(documentId)));
        }
        boolean isCreator = document.getCreatedByAccount().equals(securityHelper.getCurrentUserId());
        if (!isCreator && !hasAdminRole()) {
            return Optional.of(VaultProblems.of403NotCreator());
        }
        documentRepository.delete(document);
        return Optional.empty();
    }

    private boolean hasAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + adminRoleName));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
