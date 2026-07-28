package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.core.States.HabState;

/**
 * Drives the ATTEST step SYNCHRONOUSLY, in the request thread — the same treatment as
 * {@link KeriCredentialService#presentCredential}: freezes the target's metadata via its
 * {@link AttestationTargetProvider}, sends a remotesign anchoring request to the linked wallet AID,
 * waits IN-THREAD for the wallet's confirmed KEL interaction event, verifies it actually anchors the
 * expected digest, and advances the ceremony to {@code ATTEST_ANCHORED} before returning — all in
 * {@link #attest}, the only entry point. There is no longer a background continuation to dispatch to:
 * a crash mid-flight simply abandons this one HTTP request, leaving the ceremony at
 * {@code ATTEST_REQUESTED} for a retry (or the cleanup sweep) to resolve.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriAttestService {

    private static final List<String> REMOTESIGN_REF_ROUTES =
            List.of("/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");
    private static final String REMOTESIGN_TOPIC = "remotesign";
    private static final String REMOTESIGN_REQUEST_ROUTE = "/remotesign/ixn/req";

    /** Short wait before re-sending, to catch a late-arriving matching notification. Deliberately
     *  much shorter than {@link KeriAttestationProperties#remotesignTimeout()}, since it only catches
     *  a reply that arrived after a previous attempt gave up but before the user hit retry. */
    private static final Duration RETRY_PRECHECK_TIMEOUT = Duration.ofSeconds(2);

    private static final int KEY_STATE_QUERY_ATTEMPTS = 5;
    private static final long KEY_STATE_QUERY_WAIT_MILLIS = 10_000L;

    /** The {@code KERI_DATETIME} pattern — see
     *  {@link KeriCredentialService#KERI_DATETIME}'s javadoc for why this is passed explicitly rather
     *  than left to the pinned signify jar's own null-datetime fallback. */
    private static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");

    private final KeriAttestationClient client;
    private final KeriAgentService agentService;
    private final RemotesignRequestFactory kedFactory;
    private final AttestationTargetProviderRegistry providerRegistry;
    private final KeriNotificationCorrelator correlator;
    private final CeremonyService ceremonyService;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final KeriOobiService oobiService;

    /** A candidate anchoring-event locator learned from the wallet's ref exn (or, failing that, a
     *  key-state query fallback) — either field may be {@code null} if unavailable. */
    private record AnchorCandidate(String said, String sequence) {
        private static final AnchorCandidate EMPTY = new AnchorCandidate(null, null);

        boolean isEmpty() {
            return said == null && sequence == null;
        }
    }

    /**
     * Orchestrates the ATTEST step end-to-end, SYNCHRONOUSLY, for a single controller call: {@link
     * CeremonyService#beginStep} from {@code AUTH_BEGIN_CONFIRMED} to {@code ATTEST_REQUESTED} → a
     * short retry pre-check for a late-arriving ref → authorize the target → freeze/digest it → build
     * the saidified remotesign KED ({@link RemotesignRequestFactory}) → send it → wait IN-THREAD for the
     * wallet's ref → resolve and verify the anchoring KEL event → complete the step. Every wire step
     * logs at INFO so a stalled live run shows exactly where it stopped.
     *
     * @return {@link Either#right} with the ceremony's final view once ATTEST_ANCHORED is reached;
     *         {@link Either#left} with the problem on any failure — the ceremony is always left in a
     *         terminal ({@code FAILED}) or successfully-advanced ({@code ATTEST_ANCHORED}) state before
     *         this method returns, never stuck at {@code ATTEST_REQUESTED}.
     */
    public Either<ProblemDetail, CeremonyView> attest(String ceremonyId, String userId, boolean retry) {
        Either<ProblemDetail, KeriAttestationCeremonyEntity> begun = ceremonyService.beginStep(ceremonyId, userId,
                CeremonyState.AUTH_BEGIN_CONFIRMED, CeremonyState.ATTEST_REQUESTED, retry);
        if (begun.isLeft()) {
            return Either.left(begun.getLeft());
        }
        KeriAttestationCeremonyEntity ceremony = begun.get();
        int generation = ceremony.getAttemptGeneration();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            return failAttest(ceremonyId, generation, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to attest with.".formatted(userId));
        }
        String walletAid = linkOpt.get().getAid();
        String walletOobiUrl = linkOpt.get().getOobiUrl();

        // Retry pre-check: before re-sending, look for a late-arriving ref for whatever was
        // sent on a previous attempt. This wait is route-only (see KeriNotificationCorrelator#awaitByRoute)
        // and — unlike the main wait below — cannot be given an exclude snapshot, because the late ref it
        // is looking for is itself pre-existing. So a claimed ref here may be STALE DEBRIS rather than a
        // genuine late reply; it is therefore resumed ONLY IF it actually anchors THIS ceremony's payload.
        // A non-anchoring ref must not fail the ceremony: that would loop forever on the same undeleted
        // debris (a failed resolve never marks/deletes it), so it falls through to re-sending below. A
        // fresh (non-retry) call, or a retry whose previous attempt never sent anything, has no
        // requestExnSaid yet and skips straight to rebuilding + sending below.
        if (retry && ceremony.getRequestExnSaid() != null) {
            Optional<CorrelatedNotification> lateRef = correlator.awaitByRoute(REMOTESIGN_REF_ROUTES,
                    RETRY_PRECHECK_TIMEOUT);
            if (lateRef.isPresent()) {
                if (ceremony.getKelFloorSequence() == null) {
                    // F4: a pre-upgrade ceremony with no floor must hard-fail, never resume or re-send.
                    return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                            "no sequence floor recorded — re-attest");
                }
                Optional<Map<String, Object>> anchor = Optional.empty();
                try {
                    refreshWalletKeyState(userId, walletAid, walletOobiUrl);
                    anchor = locateAnchoringEvent(walletAid, ceremony.getPayloadSaid(),
                            ceremony.getKelFloorSequence(), lateRef.get());
                } catch (InterruptedException e) {
                    // Match the rest of this class's interrupt convention: abort promptly rather than
                    // falling through into a fresh send + long main wait.
                    Thread.currentThread().interrupt();
                    return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                            "Interrupted during the ATTEST retry pre-check.");
                } catch (Exception e) {
                    log.warn("retry pre-check verification errored for ceremony {}: {} — re-sending", ceremonyId,
                            e.getMessage());
                }
                if (anchor.isPresent()) {
                    log.info("remotesign ref received {} (late, resumed from retry)", lateRef.get().exnSaid());
                    return completeAnchored(ceremonyId, userId, generation, walletAid, anchor.get(), lateRef.get());
                }
                log.info("retry pre-check: a claimed ref did not anchor payload {} — re-sending",
                        ceremony.getPayloadSaid());
            }
        }

        Optional<AttestationTargetProvider> providerOpt = providerRegistry.forType(ceremony.getTargetType());
        if (providerOpt.isEmpty()) {
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "No AttestationTargetProvider registered for target type %s."
                            .formatted(ceremony.getTargetType()));
        }
        AttestationTargetProvider provider = providerOpt.get();

        Optional<ProblemDetail> authFailure = provider.authorize(ceremony.getTargetId(), userId);
        if (authFailure.isPresent()) {
            ProblemDetail problem = authFailure.get();
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED, problem.getTitle(),
                    problem.getDetail());
            return Either.left(problem);
        }

        Either<ProblemDetail, AttestationDigest> digestResult =
                provider.prepareDigest(ceremony.getTargetId(), ceremonyId);
        if (digestResult.isLeft()) {
            ProblemDetail problem = digestResult.getLeft();
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED, problem.getTitle(),
                    problem.getDetail());
            return Either.left(problem);
        }
        AttestationDigest digest = digestResult.get();
        boolean digestPersisted = ceremonyService.updateWaitingStepData(ceremonyId, generation,
                CeremonyState.ATTEST_REQUESTED, c -> {
                    c.setMetadataDigest(digest.digestQb64());
                    c.setMetadataLabel(digest.metadataLabel());
                });
        if (!digestPersisted) {
            return Either.left(staleCeremonyProblem(ceremonyId));
        }

        // query the wallet's CURRENT KEL sequence and persist it as a floor BEFORE the
        // remotesign request is sent. resolveAndComplete requires any accepted anchoring event to be
        // strictly after this floor — without it, an old KEL event that happens to carry the same
        // metadata digest (e.g. left over from a prior attestation of identical content) could satisfy a
        // fresh request that the wallet never actually acted on.
        Optional<String> floorSequence = queryLatestSequenceWithRetries(walletAid);
        if (floorSequence.isEmpty()) {
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "Failed to query the wallet's current KEL sequence to establish an anchor floor.");
        }
        boolean floorPersisted = ceremonyService.updateWaitingStepData(ceremonyId, generation,
                CeremonyState.ATTEST_REQUESTED, c -> c.setKelFloorSequence(floorSequence.get()));
        if (!floorPersisted) {
            return Either.left(staleCeremonyProblem(ceremonyId));
        }

        // Snapshot the remotesign-ref notifications already sitting in the agent's queue BEFORE sending
        // this request. The wait below excludes them, so a leftover unread ref from an earlier
        // failed/timed-out attempt (which resolveAndComplete never marked/deleted, since that only
        // happens on success) cannot be claimed as THIS request's reply — which would otherwise resolve
        // against the current payload SAID, find no matching ixn, and fail with ATTEST_SEAL_MISMATCH
        // before the wallet has even signed.
        Set<String> preexistingRefs = correlator.outstandingNoteIds(REMOTESIGN_REF_ROUTES);

        String payloadSaid;
        try {
            Optional<HabState> senderOpt = client.client().identifiers().get(agentService.agentName());
            if (senderOpt.isEmpty()) {
                return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                        "No local HabState found for agent identifier %s.".formatted(agentService.agentName()));
            }

            // Wallet contract: the payload itself is saidified before it is ever
            // sent, and that payload SAID (not the raw metadataDigest) is what the wallet is expected to
            // anchor as the interaction-event seal — see RemotesignRequestFactory's javadoc.
            Map<String, Object> ked = kedFactory.anchorRequestKed(walletAid, digest.metadataLabel(), digest.digestQb64());
            payloadSaid = (String) ked.get("d");
            ExchangeMessageResult built = client.client().exchanges().createExchangeMessage(senderOpt.get(),
                    REMOTESIGN_REQUEST_ROUTE, ked, Map.of(), walletAid, nowKeriTimestamp(), null);

            // Persist BEFORE the send completes: the SAID is deterministic from
            // the built (not-yet-sent) exn, matching KeriCredentialService#sendApply's idiom.
            String requestExnSaid = (String) built.exn().getKed().get("d");
            boolean exnPersisted = ceremonyService.updateWaitingStepData(ceremonyId, generation,
                    CeremonyState.ATTEST_REQUESTED, c -> {
                        c.setRequestExnSaid(requestExnSaid);
                        c.setPayloadSaid(payloadSaid);
                    });
            if (!exnPersisted) {
                return Either.left(staleCeremonyProblem(ceremonyId));
            }

            client.client().exchanges().sendFromEvents(agentService.agentName(), REMOTESIGN_TOPIC, built.exn(),
                    built.sigs(), built.atc(), List.of(walletAid));
            log.info("remotesign request sent to {}", walletAid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "Interrupted while sending the ATTEST remotesign request: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to send ATTEST remotesign request for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "Failed to send the ATTEST remotesign request: " + e.getMessage());
        }

        log.info("waiting for remotesign ref (routes {})", REMOTESIGN_REF_ROUTES);
        Optional<CorrelatedNotification> ref = correlator.awaitByRoute(REMOTESIGN_REF_ROUTES,
                properties.remotesignTimeout(), preexistingRefs);
        if (ref.isEmpty()) {
            return failAttest(ceremonyId, generation, KeriAttestationProblems.KERI_WALLET_TIMEOUT,
                    "Timed out waiting for the wallet's remotesign ref.");
        }
        log.info("remotesign ref received {}", ref.get().exnSaid());

        return resolveAndComplete(ceremonyId, userId, generation, walletAid, walletOobiUrl, payloadSaid,
                floorSequence.get(), ref.get());
    }

    // --- from a correlated ref, locate + verify the anchoring KEL event, then complete ---

    /**
     * Locates and verifies the anchoring KEL event from the correlated ref rather than from the latest
     * key state alone, checked against {@code floorSequence}:
     * <ol>
     *   <li>If the ref exn payload yields an explicit candidate ({@link #extractCandidate}), that
     *       candidate MUST resolve to a KEL event whose sequence is <b>strictly after</b>
     *       {@code floorSequence} AND whose seal contains {@code payloadSaid}. If it doesn't — wrong
     *       event, at-or-before the floor, or simply not found — this fails immediately with
     *       {@code ATTEST_SEAL_MISMATCH}; there is no fallback shopping for a different event once an
     *       explicit candidate is on the table.</li>
     *   <li>If the ref exn payload yields no candidate at all, this falls back to a <b>bounded</b> scan
     *       of {@code ixn} events strictly after {@code floorSequence} and at or before the wallet's
     *       current key-state sequence, looking for one whose seal contains {@code payloadSaid}.</li>
     * </ol>
     * <p>A {@code null} floor hard-fails before the candidate or the KEL is even looked at; it can
     * only occur for a ceremony that reached {@code ATTEST_REQUESTED} before the column existed.
     */
    private Either<ProblemDetail, CeremonyView> resolveAndComplete(String ceremonyId, String userId, int generation,
            String walletAid, String walletOobiUrl, String payloadSaid, String floorSequence,
            CorrelatedNotification ref) {
        try {
            if (floorSequence == null) {
                return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                        "no sequence floor recorded — re-attest");
            }
            refreshWalletKeyState(userId, walletAid, walletOobiUrl);
            Optional<Map<String, Object>> event = locateAnchoringEvent(walletAid, payloadSaid, floorSequence, ref);
            if (event.isEmpty()) {
                return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                        ("No interaction (ixn) event strictly after the floor sequence and at or before the wallet's "
                                + "current key state anchored payload SAID %s (checked the wallet ref's explicit "
                                + "candidate and a bounded scan).").formatted(payloadSaid));
            }
            return completeAnchored(ceremonyId, userId, generation, walletAid, event.get(), ref);
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.warn("Failed to resolve ATTEST anchor for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                    "Error verifying the wallet's anchor: " + e.getMessage());
        }
    }

    /**
     * Re-resolves the wallet's OOBI on our agent right before its KEL is read (user-directed fix). The
     * wallet is on a DIFFERENT KERIA by design, so our agent's view of the wallet's KEL can lag the ixn
     * the wallet just anchored in response to the remotesign request — reading it without refreshing
     * would miss that event and fail ATTEST_SEAL_MISMATCH even though the wallet DID sign. Re-resolving
     * the OOBI refreshes the contact's key state / endpoints so the subsequent {@link #fetchKel} /
     * key-state query sees the fresh interaction event. Best-effort and with no ceremony side effects: a
     * resolve failure (or a link with no stored OOBI) is logged and the read proceeds on whatever state
     * the agent already has — mirrors {@code KeriCredentialService}'s own pre-presentation re-resolve.
     */
    private void refreshWalletKeyState(String userId, String walletAid, String walletOobiUrl) {
        if (walletOobiUrl == null || walletOobiUrl.isBlank()) {
            return;
        }
        log.info("re-resolving wallet OOBI before reading its KEL (aid {})", walletAid);
        Either<ProblemDetail, Void> refreshed = oobiService.refreshResolve(userId, walletOobiUrl, walletAid);
        if (refreshed.isLeft()) {
            log.warn("wallet OOBI re-resolve before KEL read failed (proceeding best-effort on current state): {}",
                    refreshed.getLeft().getDetail());
        } else {
            log.info("wallet OOBI re-resolved before KEL read");
        }
    }

    /**
     * Locates the wallet KEL interaction (ixn) event that anchors {@code payloadSaid} strictly after
     * {@code floorSequence} for this {@code ref}: first honoring the ref's explicit anchoring-event
     * candidate ({@link #extractCandidate}) if it has one — with no fallback to a scan once a candidate
     * is on the table — otherwise a bounded scan up to the wallet's current key state. Empty when none
     * verifies (wrong/missing candidate, sequence at or before the floor, or seal missing the payload
     * SAID). Pure (no ceremony side effects), so callers can use it to decide whether a claimed ref is
     * genuinely this ceremony's anchor before acting on it — the retry pre-check relies on this to avoid
     * failing the ceremony on a stale, non-matching ref.
     */
    private Optional<Map<String, Object>> locateAnchoringEvent(String walletAid, String payloadSaid,
            String floorSequence, CorrelatedNotification ref) throws Exception {
        AnchorCandidate candidate = extractCandidate(ref.exn());
        List<Map<String, Object>> kel = fetchKel(walletAid);
        List<Map<String, Object>> ixnEvents = kel.stream().filter(ke -> "ixn".equals(ke.get("t"))).toList();
        if (!candidate.isEmpty()) {
            Map<String, Object> event = locateExplicitCandidate(ixnEvents, candidate);
            return event != null && satisfiesFloorAndDigest(event, floorSequence, payloadSaid)
                    ? Optional.of(event) : Optional.empty();
        }
        return queryLatestSequenceWithRetries(walletAid)
                .map(cs -> scanForSealMatch(ixnEvents, floorSequence, cs, payloadSaid));
    }

    /**
     * Completes the ATTEST step from a verified anchoring {@code event}: persists the KEL coordinates and
     * the attester AID, then — only on a successful, non-superseded transition — marks the ref
     * notification consumed.
     *
     * <p>The persisted {@code attesterAid} is the {@code walletAid} resolved from the identity link
     * before the wait began, which makes {@code ConsumedAttestation.aid} immutable once anchored: a
     * later relink changes the link's current AID but never this ceremony's attester. On a stale
     * compare-and-set the notification is left unread so the winning attempt can still claim it.
     */
    private Either<ProblemDetail, CeremonyView> completeAnchored(String ceremonyId, String userId, int generation,
            String walletAid, Map<String, Object> event, CorrelatedNotification ref) {
        String sequence = String.valueOf(event.get("s"));
        String eventSaid = String.valueOf(event.get("d"));
        log.info("anchor verified: seq={}, eventSaid={}", sequence, eventSaid);
        boolean completed = ceremonyService.completeStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                CeremonyState.ATTEST_ANCHORED, c -> {
                    c.setKelSequence(sequence);
                    c.setKelEventSaid(eventSaid);
                    c.setAttesterAid(walletAid);
                });
        if (!completed) {
            return Either.left(staleCeremonyProblem(ceremonyId));
        }
        log.info("attest step complete");
        correlator.markAndDelete(ref.notificationId());
        return ceremonyService.get(ceremonyId, userId);
    }

    // --- candidate extraction from the ref exn payload ---

    @SuppressWarnings("unchecked")
    private static AnchorCandidate extractCandidate(Map<String, Object> exn) {
        Object a = exn.get("a");
        if (!(a instanceof Map<?, ?> payload)) {
            return AnchorCandidate.EMPTY;
        }
        Object said = ((Map<String, Object>) payload).get("d");
        Object sequence = ((Map<String, Object>) payload).get("s");
        return new AnchorCandidate(said instanceof String s ? s : null,
                sequence != null ? String.valueOf(sequence) : null);
    }

    // --- key-state fallback: bounded retries confirming KEL availability ---

    private Optional<String> queryLatestSequenceWithRetries(String aid) {
        Duration delay = properties.keyStateRetryInitialDelay();
        for (int attempt = 1; attempt <= KEY_STATE_QUERY_ATTEMPTS; attempt++) {
            try {
                Object raw = client.client().keyStates().query(aid, null);
                Operation<Object> op = client.client().operations().wait(Operation.fromObject(raw), boundedKeyStateWait());
                Object response = op.getResponse();
                if (response instanceof Map<?, ?> map) {
                    Object sn = map.get("s");
                    if (sn != null) {
                        return Optional.of(String.valueOf(sn));
                    }
                }
            } catch (Exception e) {
                log.warn("Key-state query attempt {}/{} for AID {} failed: {}", attempt, KEY_STATE_QUERY_ATTEMPTS,
                        aid, e.getMessage());
            }
            if (attempt < KEY_STATE_QUERY_ATTEMPTS) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                delay = properties.keyStateRetryInterval();
            }
        }
        return Optional.empty();
    }

    private static Operations.WaitOptions boundedKeyStateWait() {
        return Operations.WaitOptions.builder()
                .abortSignal(Operations.AbortSignal.builder().timeout(KEY_STATE_QUERY_WAIT_MILLIS).build())
                .build();
    }

    // --- KEL fetch + anchoring-event location, coded defensively over the pinned jar's raw Object
    //     shapes (field names confirmed against a live agent response) ---

    private List<Map<String, Object>> fetchKel(String aid) throws Exception {
        Object raw = client.client().keyEvents().get(aid);
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object item : rawList) {
            Map<String, Object> ked = extractKed(item);
            if (ked != null) {
                events.add(ked);
            }
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractKed(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }
        Object ked = map.get("ked");
        if (ked instanceof Map<?, ?>) {
            return (Map<String, Object>) ked;
        }
        // Defensive fallback: some KERIA responses may not wrap events under "ked" — if this item
        // already looks like a key event itself (has a type and sequence), use it directly.
        if (map.containsKey("t") && map.containsKey("s")) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /** Locates the {@code ixn} event an explicit ref-derived {@code candidate} names — by SAID first,
     *  then by sequence (no fallback to "the latest event" if neither matches — a caller with an
     *  explicit candidate that can't be found must fail, not shop for a different event). */
    private static Map<String, Object> locateExplicitCandidate(List<Map<String, Object>> ixnEvents,
            AnchorCandidate candidate) {
        if (candidate.said() != null) {
            for (Map<String, Object> ke : ixnEvents) {
                if (candidate.said().equals(ke.get("d"))) {
                    return ke;
                }
            }
        }
        if (candidate.sequence() != null) {
            for (Map<String, Object> ke : ixnEvents) {
                if (candidate.sequence().equals(String.valueOf(ke.get("s")))) {
                    return ke;
                }
            }
        }
        return null;
    }

    /** Bounded scan over {@code ixnEvents} whose sequence falls within
     *  ({@code floorSequence}, {@code currentSequence}] — <b>strictly</b> after the floor, at or before
     *  the current key-state sequence (hex-compared numerically) — returning the first whose seal
     *  contains {@code payloadSaid}, or {@code null} if none in that window match. */
    private static Map<String, Object> scanForSealMatch(List<Map<String, Object>> ixnEvents, String floorSequence,
            String currentSequence, String payloadSaid) {
        long floor = parseHexSequence(floorSequence);
        long current = parseHexSequence(currentSequence);
        for (Map<String, Object> ke : ixnEvents) {
            long sn = parseHexSequence(String.valueOf(ke.get("s")));
            if (sn <= floor || sn > current) {
                continue;
            }
            if (sealContainsDigest(ke.get("a"), payloadSaid)) {
                return ke;
            }
        }
        return null;
    }

    /** {@code event}'s sequence must be <b>strictly after</b> {@code floorSequence} (hex-compared
     *  numerically) AND its seal must contain {@code payloadSaid}. Sequence equal to the floor is
     *  rejected: the floor is the sequence observed before the request was sent, so the genuine
     *  anchoring event is always strictly newer. */
    private static boolean satisfiesFloorAndDigest(Map<String, Object> event, String floorSequence,
            String payloadSaid) {
        long sn = parseHexSequence(String.valueOf(event.get("s")));
        if (sn <= parseHexSequence(floorSequence)) {
            return false;
        }
        return sealContainsDigest(event.get("a"), payloadSaid);
    }

    private static long parseHexSequence(String hexSequence) {
        return Long.parseLong(hexSequence, 16);
    }

    private static boolean sealContainsDigest(Object sealField, String digestQb64) {
        if (!(sealField instanceof List<?> seals) || digestQb64 == null) {
            return false;
        }
        for (Object seal : seals) {
            if (seal instanceof Map<?, ?> sealMap && digestQb64.equals(sealMap.get("d"))) {
                return true;
            }
        }
        return false;
    }

    // --- internals ---

    private Either<ProblemDetail, CeremonyView> failAttest(String ceremonyId, int generation, String title,
            String detail) {
        ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED, title, detail);
        return Either.left(KeriAttestationProblems.unprocessable(title, detail));
    }

    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /** See {@link #KERI_DATETIME}'s javadoc. */
    private static String nowKeriTimestamp() {
        return KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** The guarded update found the ceremony no longer waiting on {@code ATTEST_REQUESTED}: a
     *  concurrent retry or sweep transition beat this attempt to it. */
    private static ProblemDetail staleCeremonyProblem(String ceremonyId) {
        return KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE,
                "Ceremony %s is no longer waiting on the expected step.".formatted(ceremonyId));
    }
}
