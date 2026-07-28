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
import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
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
    private final VaultKeyLookupService keyLookupService;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final ApplicationEventPublisher eventPublisher;
    /** Optional: only present when blockchain_publisher is wired up with an IPFS publisher in this deployment. */
    private final ObjectProvider<IpfsAvailability> ipfsAvailability;
    /**
     * Optional: only present when blockchain_publisher implements it (conditional on
     * {@code lob.keri-attestation.enabled}, design §5.1, Task 14). A plain (bodiless) publish never
     * consults this — it is read only when {@code publish} is called with an attestationCeremonyId.
     */
    private final ObjectProvider<AttestationFreezeGuard> attestationFreezeGuardProvider;
    /**
     * Optional: only present when keri_attestation is enabled (design §5.1, Task 14). Same
     * bodiless-publish-never-touches-this rule as {@link #attestationFreezeGuardProvider}.
     */
    private final ObjectProvider<AttestationConsumptionApi> attestationConsumptionApiProvider;

    /** {@code AttestationTargetProvider#targetType()} for documents (blockchain_publisher's
     *  {@code DocumentAttestationTargetProvider.TARGET_TYPE}) — duplicated as a literal rather than
     *  imported: document_vault must not depend on blockchain_publisher (it is the other way around),
     *  so this string is the one place the two modules must be kept in sync by convention. */
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

        // A slot may wrap to an organisation key or to an addressbook contact; the lookup spans both, and
        // this check does not care which it got — only that it belongs to this organisation.
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
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek()))
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
     * Design §5.1 (Task 14): with a null (genuinely OMITTED — no body at all, an empty JSON object,
     * or the field simply absent) {@code attestationCeremonyId} this is byte-for-byte the pre-Task-14
     * {@code publish(String)} — the attested branch below is skipped entirely, so a bodiless publish
     * never touches {@link #attestationFreezeGuardProvider} or {@link #attestationConsumptionApiProvider},
     * not even to probe whether they are wired up.
     *
     * <p><b>A present-but-blank {@code attestationCeremonyId} is rejected outright (M3 cross-review
     * F6 fix)</b> — {@code ATTESTATION_CEREMONY_ID_BLANK} (422) — rather than silently normalized to
     * null the way {@link #list}'s free-text {@code q} parameter is. Unlike {@code q} (where blank
     * and absent are genuinely interchangeable — "no filter" either way), a blank ceremony id here is
     * very likely a caller's mistake (e.g. a client sending {@code {"attestationCeremonyId": ""}}
     * when it meant to supply a real one); silently falling through to a plain, unattested publish
     * would hide that mistake behind an apparently-successful response instead of surfacing it.
     *
     * <p>With a non-null, non-blank ceremony id, all of the following run INSIDE this same row-locked
     * transaction, after the existing org/DRAFT/IPFS checks and before the document is flipped to
     * {@code PUBLISHED} — any failure returns left with the document left untouched (still DRAFT,
     * no event fired):
     * <ol>
     *   <li>Both {@link AttestationFreezeGuard} and {@link AttestationConsumptionApi} must be wired
     *       up (module enabled) — otherwise {@code ATTESTATION_UNAVAILABLE} (422).</li>
     *   <li>{@link AttestationFreezeGuard#verifyFreshness} — the frozen envelope fingerprint (and
     *       its age) must still be valid (design §5.2).</li>
     *   <li>{@link AttestationConsumptionApi#validateAndConsume} — ceremony ownership/state/target/
     *       binding-version checks plus the compare-and-set to {@code CONSUMED}.</li>
     * </ol>
     * Only once all three pass is {@code attestationCeremonyId} persisted on the document row and
     * carried into the emitted {@link DocumentPublishCommand} (which
     * {@code DocumentDispatchRetryJob}'s re-emission automatically carries forward too, via the same
     * {@link #toPublishCommand} factory).
     */
    public Either<ProblemDetail, DocumentView> publish(String documentId, @Nullable String attestationCeremonyIdOrBlank) {
        // F6 fix: a present-but-blank id is rejected outright, checked before ANY database access —
        // pure input validation on the request itself, not a document/ceremony-state question.
        if (attestationCeremonyIdOrBlank != null && attestationCeremonyIdOrBlank.isBlank()) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.ATTESTATION_CEREMONY_ID_BLANK,
                    "attestationCeremonyId must not be blank; omit the field entirely for a plain publish."));
        }
        String attestationCeremonyId = attestationCeremonyIdOrBlank;
        // Row lock FIRST (findByIdForUpdate, not findById): two concurrent publish calls must not
        // both observe DRAFT and both fire the irreversible DocumentPublishCommand. Under the
        // class-level @Transactional, a second concurrent caller blocks here until the first commits,
        // then reads PUBLISHED below and returns ALREADY_PUBLISHED instead of double-publishing.
        Optional<VaultDocumentEntity> documentM = documentRepository.findByIdForUpdate(documentId);
        if (documentM.isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.DOCUMENT_NOT_FOUND,
                    "No document %s.".formatted(documentId)));
        }
        VaultDocumentEntity document = documentM.get();
        // Auth before capability: org membership is checked before the IPFS availability probe below,
        // so a non-member (or someone probing a random documentId) cannot learn whether IPFS is
        // configured in this deployment. Matches fetch()/delete()'s existing auth-first ordering.
        if (!securityHelper.canUserAccessOrg(document.getOrganisationId())) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(document.getOrganisationId())));
        }
        if (document.getStatus() != VaultDocumentStatus.DRAFT) {
            return Either.left(VaultProblems.conflict(VaultProblems.ALREADY_PUBLISHED,
                    "Document %s is already published.".formatted(documentId)));
        }
        IpfsAvailability ipfs = ipfsAvailability.getIfAvailable();
        if (ipfs == null || !ipfs.isAvailable()) {
            return Either.left(VaultProblems.serviceUnavailable(VaultProblems.DOCUMENT_PUBLISHING_UNAVAILABLE,
                    "Document publishing requires a configured IPFS publisher; none is available in this deployment."));
        }

        if (attestationCeremonyId != null) {
            Either<ProblemDetail, Void> attestation = consumeAttestation(document, attestationCeremonyId);
            if (attestation.isLeft()) {
                return Either.left(attestation.getLeft());
            }
            document.setAttestationCeremonyId(attestationCeremonyId);
        }

        document.setStatus(VaultDocumentStatus.PUBLISHED);
        document.setPublishedAt(LocalDateTime.now());
        document.setLedgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH);
        VaultDocumentEntity saved = documentRepository.save(document);

        eventPublisher.publishEvent(toPublishCommand(saved));

        return Either.right(toView(saved));
    }

    /**
     * The fail-closed attested-publish gate (design §5.1 step 2, Task 14): freshness guard, then
     * ceremony consumption. Neither the document nor the ceremony is touched on any left path — the
     * document is still DRAFT (its status flip happens only after this returns right, back in
     * {@link #publish(String, String)}), and {@code validateAndConsume}'s compare-and-set means the
     * ceremony itself only ever advances to {@code CONSUMED} on the right path.
     *
     * <p><b>Every CLIENT-range (400-499) Left is forced to HTTP 422 (M3 cross-review F7 fix); a 5xx
     * Left passes through unchanged (R5 fix, Codex re-verification).</b> {@link
     * AttestationFreezeGuard#verifyFreshness} and {@code keri_attestation}'s {@code
     * AttestationConsumptionApi#validateAndConsume} build their own {@link ProblemDetail}s with
     * whatever native status fits THEIR domain — {@code CEREMONY_NOT_FOUND} is 404,
     * {@code CEREMONY_FORBIDDEN} is 403, {@code IDENTITY_RELINKED}/{@code CEREMONY_EXPIRED}/
     * {@code CEREMONY_INVALID_STATE} are 409, etc. Design §5.1 draws the status boundary at THIS
     * module instead for those: every attested-publish failure that is the CALLER's fault, regardless
     * of its origin, is a 422 to the caller of {@code publish} — the frontend switches on the
     * problem's TITLE, never its status, so collapsing the status here costs it nothing while keeping
     * "this HTTP verb/resource failed" a single, predictable code for client mistakes. A 5xx (e.g. a
     * downstream KERI agent outage surfaced as {@code SERVICE_UNAVAILABLE}) is NOT the caller's fault
     * — forcing it to 422 would tell a retrying client "fix your request", when the correct signal is
     * "this is transient, try again later"; those pass through with their original status untouched.
     * The original title and detail are always preserved verbatim; only the status is ever
     * overwritten, and only within the 4xx range.
     */
    private Either<ProblemDetail, Void> consumeAttestation(VaultDocumentEntity document, String attestationCeremonyId) {
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

        return Either.right(null);
    }

    /** F7 fix, guarded by R5 (Codex re-verification): only a client-range status (400-499) is rebuilt
     *  with its title/detail preserved but its status forced to 422 — a 5xx {@code original} is
     *  returned as-is, untouched. See {@link #consumeAttestation}'s javadoc for why. */
    private static ProblemDetail capClientErrorAt422(ProblemDetail original) {
        int status = original.getStatus();
        if (status < 400 || status > 499) {
            return original;
        }
        return VaultProblems.unprocessable(original.getTitle(), original.getDetail());
    }

    /**
     * Read-only load for KERI wallet-attestation (design §5.2, Task 13): existence, org-membership
     * and DRAFT-status checks identical to {@link #publish(String)}'s early guards (same
     * {@code documentRepository}/{@code securityHelper} idiom, same {@link VaultProblems}), but
     * without ANY of {@code publish}'s side effects — no status flip, no
     * {@link DocumentPublishCommand} event, no IPFS-availability probe. Called from
     * {@code blockchain_publisher}'s {@code DocumentAttestationTargetProvider} both to authorize a
     * ceremony (design §3.3 {@code authorize}) and, immediately before freezing, to load the exact
     * document row to build the envelope from (design §3.3 {@code prepareDigest}) — at ATTEST time
     * the document is still DRAFT in this module; no publisher-side row exists yet, so the freeze
     * step cannot go through {@code publish()} or any publisher-side lookup.
     *
     * <p>Deliberately a plain {@code findById}, not {@code findByIdForUpdate}: this never mutates the
     * document, and documents have no in-place edit path once uploaded (blueprint B3), so there is
     * nothing here for a row lock to serialize against. The real double-check happens where it must —
     * inside {@code publish()}'s own locked transaction, when a ceremony is actually consumed.
     *
     * <p>{@code userId} is accepted (rather than derived internally) to match the
     * {@code AttestationTargetProvider} port's {@code authorize(targetId, userId)} shape (design
     * §3.3) it backs; like every other authorization check in this class, the actual org-membership
     * decision is {@link KeycloakSecurityHelper#canUserAccessOrg(String)}, which reads the request's
     * current {@code SecurityContextHolder} JWT rather than an arbitrary caller-supplied id — correct
     * here because both call sites (ceremony creation and the ATTEST step's synchronous
     * authorize-then-freeze) run in the same request thread as the user identified by that JWT.
     *
     * <p><b>Contract: the returned entity is fully initialized.</b> {@code blockchain_publisher}'s
     * {@code DocumentAttestationTargetProvider} maps this entity (via {@link #toPublishCommand}) well
     * after this method's own {@code @Transactional(readOnly = true)} boundary has committed and
     * closed - a cross-module method call, not an HTTP request, so Spring Boot's
     * {@code open-in-view} default (which only keeps a session open for the life of a servlet
     * request) does not help here. {@code VaultDocumentEntity#getSlots()} is a
     * {@code LAZY @ElementCollection}; it is eagerly touched below, inside this method's own
     * transaction, precisely so callers may safely read it after this method returns - without that,
     * the lazy collection would be a detached, uninitialized proxy the moment this transaction closes,
     * throwing {@code LazyInitializationException} on first access outside a session.
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
        // Eagerly initialize the LAZY slots collection inside this transaction - see the contract
        // note above. Hibernate.initialize is a no-op for an already-initialized/non-proxy collection
        // (e.g. in unit tests that stub the repository with a plain List), so this is always safe.
        Hibernate.initialize(document.getSlots());
        return Either.right(document);
    }

    /**
     * Builds the PII-free {@link DocumentPublishCommand} handed to blockchain_publisher. Extracted
     * (Codex adversarial-review finding 1) so this exact field mapping is shared by BOTH emission
     * sites and cannot drift apart: {@link #publish(String)} above, and {@code
     * DocumentDispatchRetryJob}, which re-emits the same command for any document stuck in
     * PUBLISHED/MARK_DISPATCH after a crash or async-rejection dropped the first emission.
     * {@code public static} (rather than private) precisely so the retry job — in the sibling
     * {@code document_vault.job} package — can call it without duplicating this block.
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
                        .map(slot -> new DocumentPublishCommand.PublishSlot(slot.getEphemeralPub(), slot.getWrappedDek()))
                        .toList(),
                document.getAttestationCeremonyId());
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
        Map<String, KeyRef> slotKeys = keyLookupService.findAllById(keyIds);

        // accountId comes first: a slot wrapped to an addressbook contact has a null account, and that
        // is the answer, not an oversight. A contact has no Reeve login to authorise, so no caller can
        // ever match one — they read published documents in the Indexer instead.
        boolean envelopeAccessible = document.getCreatedByAccount().equals(accountId)
                || slotKeys.values().stream().anyMatch(key -> accountId.equals(key.accountId()));

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
                        // accountId is null for an addressbook contact — they are a recipient without an
                        // account, which is exactly what the reader should see.
                        .map(key -> new DocumentEnvelopeView.RecipientView(key.id(), key.accountId(),
                                key.displayName(), key.label(), key.assurance()))
                        .toList(),
                document.getLedgerDispatchStatus(), document.getLedgerDispatchError(),
                document.getTxHash(), document.getIpfsCid(),
                document.getCreatedByName(), document.getCreatedAt()));
    }

    public Optional<ProblemDetail> delete(String documentId) {
        // Row lock (findByIdForUpdate, not findById): delete is the second mutating path on this
        // aggregate besides publish(), and must take the same PESSIMISTIC_WRITE lock. Without it,
        // delete() could read DRAFT while a concurrent publish() holds the lock, then — after
        // publish commits the row to PUBLISHED — issue its DELETE unconditionally, destroying a
        // published document. With the lock, delete blocks until publish commits, re-reads
        // PUBLISHED, and returns DOCUMENT_PUBLISHED_IMMUTABLE below instead.
        Optional<VaultDocumentEntity> documentM = documentRepository.findByIdForUpdate(documentId);
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
