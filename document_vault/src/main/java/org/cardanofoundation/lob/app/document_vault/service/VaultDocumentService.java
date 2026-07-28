package org.cardanofoundation.lob.app.document_vault.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
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
import org.hibernate.Hibernate;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.RecipientKeyHasher;
import org.cardanofoundation.lob.app.document_vault.domain.KeyRef;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Stores and publishes encrypted documents. The client delivers the two crypto outputs (ciphertext
 * and slots) and the server assigns the id, content-addresses, persists and indexes. Nothing here
 * may decrypt or unwrap secret material — validation is structural only.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VaultDocumentService {

    /**
     * A set, not a single value: when envelope v2 ships, 2 is added and 1 stays, so older clients
     * keep working. Unknown versions are rejected — the server cannot store a schema it does not know.
     */
    public static final Set<Integer> SUPPORTED_ENVELOPE_VERSIONS = Set.of(1);

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyLookupService keyLookupService;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * Present only when {@code lob.keri-attestation.enabled}. A plain (bodiless) publish never reads
     * this, not even to probe whether it is wired up.
     */
    private final ObjectProvider<AttestationFreezeGuard> attestationFreezeGuardProvider;
    /** Present only when {@code lob.keri-attestation.enabled}, like
     *  {@link #attestationFreezeGuardProvider}. */
    private final ObjectProvider<AttestationConsumptionApi> attestationConsumptionApiProvider;

    /** The {@code AttestationTargetProvider} target type for documents. A literal rather than an
     *  import, because document_vault must not depend on the module declaring the port constant. */
    private static final String ATTESTATION_TARGET_TYPE_DOCUMENT = "DOCUMENT";

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
        // Structural checks first: a malformed envelope should not cost an organisation lookup.
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

        // A slot may wrap to an organisation key or to an addressbook contact; the lookup spans both.
        // All that matters here is that the key belongs to this organisation.
        List<String> keyIds = request.getSlots().stream().map(UploadDocumentRequest.SlotRequest::getKeyId).toList();
        Map<String, KeyRef> keysById = keyLookupService.findAllById(keyIds);
        for (UploadDocumentRequest.SlotRequest slot : request.getSlots()) {
            KeyRef key = keysById.get(slot.getKeyId());
            if (key == null || !key.organisationId().equals(organisationId)) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s is unknown or not registered in organisation %s."
                                .formatted(slot.getKeyId(), organisationId)));
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
        // recipientKeyHash is derived from the key authorised above, never taken from the request: a
        // client-supplied hash would let an uploader stamp someone else's identifier onto a document
        // and inject it into their Indexer filter.
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek(),
                        RecipientKeyHasher.hash(keysById.get(slot.getKeyId()).publicKey())))
                .toList());

        VaultDocumentEntity saved = documentRepository.save(document);

        // Addressbook contacts drop out here: they have no account to notify. The event is an in-app
        // signal to Reeve users, and a contact reads the document as a published record in the Indexer.
        Set<String> recipientAccountIds = request.getSlots().stream()
                .map(slot -> keysById.get(slot.getKeyId()).accountId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        eventPublisher.publishEvent(new DocumentSharedEvent(saved.getId(), organisationId, recipientAccountIds));

        return Either.right(new DocumentUploadedView(saved.getId(), saved.getContentHash(), saved.getCreatedAt()));
    }

    public Either<ProblemDetail, DocumentView> publish(String documentId) {
        return publish(documentId, null);
    }

    /**
     * With a null {@code attestationCeremonyId} — no body at all, an empty JSON object, or the field
     * absent — this is exactly {@link #publish(String)}: the attested branch is skipped, so the
     * attestation providers are never consulted, not even to probe whether they are wired up.
     *
     * <p>A present-but-blank ceremony id is rejected with {@code ATTESTATION_CEREMONY_ID_BLANK}
     * rather than normalised to null, because it is far more likely a caller mistake than a request
     * for a plain publish, and falling through would hide it behind a successful response.
     *
     * <p>With a non-blank ceremony id, all of the following run inside this same row-locked
     * transaction, after the org and DRAFT checks and before the status flips to {@code PUBLISHED}.
     * Any failure returns left with the document untouched — still DRAFT, no event fired:
     * <ol>
     *   <li>Both {@link AttestationFreezeGuard} and {@link AttestationConsumptionApi} must be wired
     *       up, otherwise {@code ATTESTATION_UNAVAILABLE}.</li>
     *   <li>{@link AttestationFreezeGuard#verifyFreshness} — the frozen envelope fingerprint and its
     *       age must still be valid.</li>
     *   <li>{@link AttestationConsumptionApi#validateAndConsume} — ceremony ownership, state, target
     *       and binding checks plus the compare-and-set to {@code CONSUMED}.</li>
     * </ol>
     * Only once all three pass is the ceremony persisted on the document row and carried into the
     * emitted {@link DocumentPublishCommand}.
     */
    public Either<ProblemDetail, DocumentView> publish(String documentId, @Nullable String attestationCeremonyIdOrBlank) {
        // Pure input validation, so it runs before any database access.
        if (attestationCeremonyIdOrBlank != null && attestationCeremonyIdOrBlank.isBlank()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.ATTESTATION_CEREMONY_ID_BLANK,
                    "attestationCeremonyId must not be blank; omit the field entirely for a plain publish."));
        }
        String attestationCeremonyId = attestationCeremonyIdOrBlank;
        // Row lock first: two concurrent publishes must not both observe DRAFT and both fire the
        // irreversible DocumentPublishCommand. The second caller blocks here until the first commits,
        // then reads PUBLISHED below and returns ALREADY_PUBLISHED.
        Optional<VaultDocumentEntity> documentM = documentRepository.findByIdForUpdate(documentId);
        if (documentM.isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        if (!securityHelper.canUserAccessOrg(document.getOrganisationId())) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(document.getOrganisationId())));
        }
        if (document.getStatus() != VaultDocumentStatus.DRAFT) {
            return Either.left(VaultProblems.conflict(VaultProblems.ALREADY_PUBLISHED,
                    "Document %s is already published.".formatted(documentId)));
        }

        if (attestationCeremonyId != null) {
            Either<ProblemDetail, ConsumedAttestation> attestation = consumeAttestation(document, attestationCeremonyId);
            if (attestation.isLeft()) {
                return Either.left(attestation.getLeft());
            }
            ConsumedAttestation consumed = attestation.get();
            document.setAttestationCeremonyId(attestationCeremonyId);
            document.setAttestationAid(consumed.aid());
            document.setAttestationPayloadSaid(consumed.payloadSaid());
            document.setAttestationKelSequence(consumed.kelSequence());
        }

        document.setStatus(VaultDocumentStatus.PUBLISHED);
        document.setPublishedAt(LocalDateTime.now());
        document.setLedgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH);
        VaultDocumentEntity saved = documentRepository.save(document);

        eventPublisher.publishEvent(toPublishCommand(saved));

        return Either.right(toView(saved));
    }

    /**
     * The fail-closed attested-publish gate: freshness guard, then ceremony consumption. Nothing is
     * mutated on a left path — the document stays DRAFT, and {@code validateAndConsume}'s
     * compare-and-set only advances the ceremony to {@code CONSUMED} on the right path.
     *
     * <p>The two collaborators build problems with whatever status fits their own domain
     * ({@code CEREMONY_NOT_FOUND} 404, {@code CEREMONY_FORBIDDEN} 403, {@code CEREMONY_EXPIRED} 409).
     * Client-range statuses are collapsed to 422 here so every caller mistake on this endpoint gets
     * one predictable code; the frontend switches on the problem title, not its status. A 5xx — a
     * downstream KERI agent outage, say — passes through untouched, since telling a retrying client
     * to "fix your request" would be wrong. Title and detail are always preserved.
     */
    private Either<ProblemDetail, ConsumedAttestation> consumeAttestation(VaultDocumentEntity document, String attestationCeremonyId) {
        AttestationFreezeGuard freezeGuard = attestationFreezeGuardProvider.getIfAvailable();
        AttestationConsumptionApi consumptionApi = attestationConsumptionApiProvider.getIfAvailable();
        if (freezeGuard == null || consumptionApi == null) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.ATTESTATION_UNAVAILABLE,
                    "Attested publish requires the keri_attestation module; it is not available in this deployment."));
        }

        Optional<ProblemDetail> freshnessProblem = freezeGuard.verifyFreshness(document, attestationCeremonyId);
        if (freshnessProblem.isPresent()) {
            return Either.left(capClientErrorAt422(freshnessProblem.get()));
        }

        Either<ProblemDetail, ConsumedAttestation> consumed = consumptionApi.validateAndConsume(
                attestationCeremonyId, ATTESTATION_TARGET_TYPE_DOCUMENT, document.getId(), securityHelper.getCurrentUserId());
        if (consumed.isLeft()) {
            return Either.left(capClientErrorAt422(consumed.getLeft()));
        }

        // Returned rather than discarded: blockchain_publisher cannot look this up, so the aid,
        // payloadSaid and kelSequence ride along on the document and then on DocumentPublishCommand.
        return Either.right(consumed.get());
    }

    /** Rebuilds a client-range (400-499) problem as a 422, preserving title and detail. A 5xx is
     *  returned untouched — see {@link #consumeAttestation}. */
    private static ProblemDetail capClientErrorAt422(ProblemDetail original) {
        int status = original.getStatus();
        if (status < 400 || status > 499) {
            return original;
        }
        return VaultProblems.unprocessable(original.getTitle(), original.getDetail());
    }

    /**
     * Read-only load for the KERI wallet-attestation flow: the same existence, org-membership and
     * DRAFT checks as {@link #publish(String)}, but with none of its side effects — no status flip
     * and no {@link DocumentPublishCommand}. {@link DocumentAttestationTargetProvider} calls it both
     * to authorise a ceremony and, immediately before freezing, to load the exact row the envelope is
     * built from.
     *
     * <p>A plain {@code findById}, not {@code findByIdForUpdate}: this never mutates the document and
     * documents have no in-place edit path, so there is nothing for a row lock to serialize against.
     * The double-check that matters happens inside {@code publish()}'s locked transaction, when the
     * ceremony is actually consumed.
     *
     * <p>{@code userId} is accepted to match the {@code AttestationTargetProvider} port's
     * {@code authorize(targetId, userId)} shape. The org-membership decision itself comes from
     * {@link KeycloakSecurityHelper#canUserAccessOrg(String)}, reading the current request's JWT
     * rather than the supplied id — both call sites run on the request thread of that same user.
     *
     * <p>The returned entity is fully initialized. Callers map it after this transaction has closed,
     * across a module boundary where {@code open-in-view} does not apply, so the lazy
     * {@code slots} collection is touched here to avoid a {@code LazyInitializationException}.
     */
    @Transactional(readOnly = true)
    public Either<ProblemDetail, VaultDocumentEntity> loadForAttestation(String documentId, String userId) {
        Optional<VaultDocumentEntity> documentM = documentRepository.findById(documentId);
        if (documentM.isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        if (!securityHelper.canUserAccessOrg(document.getOrganisationId())) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(document.getOrganisationId())));
        }
        if (document.getStatus() != VaultDocumentStatus.DRAFT) {
            return Either.left(VaultProblems.conflict(VaultProblems.ALREADY_PUBLISHED,
                    "Document %s is already published.".formatted(documentId)));
        }
        // A no-op when the collection is already initialized or is a plain List, as in unit tests.
        Hibernate.initialize(document.getSlots());
        return Either.right(document);
    }

    /**
     * Builds the PII-free {@link DocumentPublishCommand} handed to blockchain_publisher. Shared by
     * both emission sites so the mapping cannot drift: {@link #publish(String)} and
     * {@code DocumentDispatchRetryJob}, which re-emits the command for any document left stuck after
     * a crash dropped the first emission.
     */
    public static DocumentPublishCommand toPublishCommand(VaultDocumentEntity document) {
        return new DocumentPublishCommand(
                document.getOrganisationId(),
                document.getId(),
                document.getEnvelopeVersion(),
                document.getContentHash(),
                document.getPlaintextHash(),
                document.getPayloadNonce(),
                Base64.getEncoder().encodeToString(document.getCiphertext()),
                document.getSlots().stream()
                        .map(slot -> new DocumentPublishCommand.PublishSlot(
                                slot.getEphemeralPub(), slot.getWrappedDek(), slot.getRecipientKeyHash()))
                        .toList(),
                document.getAttestationCeremonyId(),
                document.getAttestationCeremonyId() == null ? null
                        : new DocumentPublishCommand.ConsumedAttestationRef(
                                document.getAttestationAid(),
                                document.getAttestationPayloadSaid(),
                                document.getAttestationKelSequence()));
    }

    /**
     * Org-wide listing: every org member sees all org documents' metadata, optionally filtered by
     * direction (relative to the caller), status, or a fileName/description substring. Metadata
     * visibility does not grant ciphertext access — {@link #fetch} stays restricted.
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
     * Document detail for any org member; the ciphertext only for the creator and the recipients.
     * Decryption is strictly client-side — the backend cannot decrypt and never tries.
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
        Map<String, KeyRef> slotKeys = keyLookupService.findAllById(keyIds);

        // A slot wrapped to an addressbook contact has a null account, so no caller ever matches it:
        // a contact has no Reeve login and reads published documents in the Indexer instead.
        boolean envelopeAccessible = document.getCreatedByAccount().equals(accountId)
                || slotKeys.values().stream().anyMatch(key -> accountId.equals(key.accountId()));

        return Either.right(new DocumentEnvelopeView(
                document.getId(), document.getOrganisationId(), document.getStatus(),
                document.getEnvelopeVersion(), document.getFileName(), document.getContentType(),
                document.getDescription(), document.getSizeBytes(), document.getContentHash(),
                document.getPlaintextHash(),
                envelopeAccessible,
                // Payload and slots together are the envelope, so both go to participants only.
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
                // Who can read this: org-visible, and carries no key material.
                document.getSlots().stream()
                        .map(slot -> slotKeys.get(slot.getKeyId()))
                        .filter(Objects::nonNull)
                        // A null accountId means an addressbook contact: a recipient without an account.
                        .map(key -> new DocumentEnvelopeView.RecipientView(key.id(), key.accountId(),
                                key.displayName(), key.label(), key.assurance()))
                        .toList(),
                document.getLedgerDispatchStatus(), document.getLedgerDispatchError(),
                document.getTxHash(), document.getIpfsCid(),
                document.getCreatedByName(), document.getCreatedAt()));
    }

    public Optional<ProblemDetail> delete(String documentId) {
        // Row lock, for the same reason as publish(): without it, delete could read DRAFT while a
        // concurrent publish holds the lock and then destroy a document that publish just committed
        // as PUBLISHED. With it, delete blocks, re-reads PUBLISHED and rejects below.
        Optional<VaultDocumentEntity> documentM = documentRepository.findByIdForUpdate(documentId);
        if (documentM.isEmpty()) {
            return Optional.of(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        // Membership first: Keycloak admin roles are realm-wide, so an out-of-org admin must not delete.
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
