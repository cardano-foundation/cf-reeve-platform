package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.core.States.HabState;

/**
 * Drives the ATTEST step (design §4.6): freezes the target's metadata via its
 * {@link AttestationTargetProvider}, sends a remotesign anchoring request to the linked wallet AID,
 * and — on the async continuation, {@link #awaitAnchor} — verifies the wallet's confirmed KEL
 * interaction event actually anchors the expected digest before advancing the ceremony to
 * {@code ATTEST_ANCHORED}.
 *
 * <p>Split the same way {@link KeriCredentialService} is: {@link #startAttest} is the synchronous
 * part of the step POST (authorize, freeze, build + send the remotesign request, dispatch the async
 * continuation); {@link #awaitAnchor} is that continuation, run on
 * {@link CeremonyAsyncRunner#awaitAnchor} but also directly unit-testable and directly callable
 * (via the shared {@link #resolveAndComplete} helper) from {@link #startAttest}'s own retry
 * pre-check.
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

    /** Short wait for the retry pre-check (design §4.2 "before re-sending... checks for a
     *  late-arriving matching notification") — deliberately much shorter than
     *  {@link KeriAttestationProperties#remotesignTimeout()}, since this only catches a reply that
     *  arrived <em>after</em> a previous attempt gave up but <em>before</em> the user hit retry. */
    private static final Duration RETRY_PRECHECK_TIMEOUT = Duration.ofSeconds(2);

    private static final int KEY_STATE_QUERY_ATTEMPTS = 5;
    private static final long KEY_STATE_QUERY_WAIT_MILLIS = 10_000L;

    private final KeriAttestationClient client;
    private final KeriAgentService agentService;
    private final RemotesignRequestFactory kedFactory;
    private final AttestationTargetProviderRegistry providerRegistry;
    private final KeriNotificationCorrelator correlator;
    private final CeremonyService ceremonyService;
    private final KeriAttestationCeremonyRepository ceremonyRepository;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final CeremonyAsyncRunner asyncRunner;

    /** A candidate anchoring-event locator learned from the wallet's ref exn (or, failing that, a
     *  key-state query fallback) — either field may be {@code null} if unavailable. */
    private record AnchorCandidate(String said, String sequence) {
        private static final AnchorCandidate EMPTY = new AnchorCandidate(null, null);

        boolean isEmpty() {
            return said == null && sequence == null;
        }
    }

    // --- synchronous: authorize, freeze, build + send the remotesign request ---

    public Either<ProblemDetail, Void> startAttest(String ceremonyId, String userId, boolean retry) {
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

        // Retry pre-check (design §4.2): before re-sending, look for a late-arriving correlated ref
        // matching whatever was sent on the previous attempt. A fresh (non-retry) call, or a retry
        // whose previous attempt never got as far as sending anything, has no requestExnSaid yet and
        // skips straight to rebuilding + sending below.
        if (retry && ceremony.getRequestExnSaid() != null) {
            Optional<CorrelatedNotification> lateRef = correlator.awaitCorrelated(REMOTESIGN_REF_ROUTES, walletAid,
                    ceremony.getRequestExnSaid(), RETRY_PRECHECK_TIMEOUT);
            if (lateRef.isPresent()) {
                resolveAndComplete(ceremonyId, generation, walletAid, ceremony.getMetadataDigest(),
                        ceremony.getKelFloorSequence(), lateRef.get());
                return Either.right(null);
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
        // Routed through the guarded update (F2 fix) rather than a direct save of this detached entity:
        // a concurrent retry/sweep transition landing between beginStep's row lock releasing and this
        // write must never be silently overwritten.
        boolean digestPersisted = ceremonyService.updateWaitingStepData(ceremonyId, generation,
                CeremonyState.ATTEST_REQUESTED, c -> {
                    c.setMetadataDigest(digest.digestQb64());
                    c.setMetadataLabel(digest.metadataLabel());
                });
        if (!digestPersisted) {
            return Either.left(staleCeremonyProblem(ceremonyId));
        }

        // F5 fix: query the wallet's CURRENT KEL sequence and persist it as a floor BEFORE the
        // remotesign request is sent. awaitAnchor requires any accepted anchoring event to be at or
        // after this floor — without it, an old KEL event that happens to carry the same metadata
        // digest (e.g. left over from a prior attestation of identical content) could satisfy a fresh
        // request that the wallet never actually acted on.
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

        try {
            Optional<HabState> senderOpt = client.client().identifiers().get(agentService.agentName());
            if (senderOpt.isEmpty()) {
                return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                        "No local HabState found for agent identifier %s.".formatted(agentService.agentName()));
            }

            Map<String, Object> ked = kedFactory.anchorRequestKed(walletAid, digest.digestQb64());
            ExchangeMessageResult built = client.client().exchanges().createExchangeMessage(senderOpt.get(),
                    REMOTESIGN_REQUEST_ROUTE, ked, Map.of(), walletAid, null, null);

            // Persist BEFORE the send completes (design §4.6 step 3): the SAID is deterministic from
            // the built (not-yet-sent) exn, matching KeriCredentialService#startPresentation's idiom.
            // Guarded (F2 fix) for the same reason as the digest write above.
            String requestExnSaid = (String) built.exn().getKed().get("d");
            boolean exnPersisted = ceremonyService.updateWaitingStepData(ceremonyId, generation,
                    CeremonyState.ATTEST_REQUESTED, c -> c.setRequestExnSaid(requestExnSaid));
            if (!exnPersisted) {
                return Either.left(staleCeremonyProblem(ceremonyId));
            }

            client.client().exchanges().sendFromEvents(agentService.agentName(), REMOTESIGN_TOPIC, built.exn(), built.sigs(),
                    built.atc(), List.of(walletAid));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "Interrupted while sending the ATTEST remotesign request: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to send ATTEST remotesign request for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "Failed to send the ATTEST remotesign request: " + e.getMessage());
        }

        try {
            asyncRunner.awaitAnchor(ceremonyId, generation);
        } catch (Exception e) {
            // The executor rejected the dispatch (pool/queue saturated) — the remotesign request is
            // already sent to the wallet, but with no worker left to await its reply, the ceremony must
            // not be left non-terminal with an unhandled exception as the only signal. A retry's
            // pre-check (above) will pick up a late-arriving ref instead of resending.
            log.warn("Failed to dispatch ATTEST anchor wait for ceremony {}: {}", ceremonyId, e.getMessage());
            return failAttest(ceremonyId, generation, KeriAttestationProblems.ATTEST_REQUEST_FAILED,
                    "Failed to dispatch the anchor wait: " + e.getMessage());
        }
        return Either.right(null);
    }

    // --- asynchronous continuation: await the wallet's confirmed anchor, verify, complete ---

    /**
     * Runs unsupervised on {@link CeremonyAsyncRunner}'s background executor with no caller left to
     * report a thrown exception to — see {@link KeriCredentialService#awaitPresentation}'s javadoc for
     * the same rationale. Every external-boundary call is guarded so this method always resolves the
     * ceremony via {@code completeStep} or {@code failStep} before returning.
     */
    public void awaitAnchor(String ceremonyId, int generation) {
        Optional<KeriAttestationCeremonyEntity> ceremonyOpt = ceremonyRepository.findById(ceremonyId);
        if (ceremonyOpt.isEmpty()) {
            return;
        }
        KeriAttestationCeremonyEntity ceremony = ceremonyOpt.get();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(ceremony.getUserId());
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                    KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to await an anchor from.".formatted(ceremony.getUserId()));
            return;
        }
        String walletAid = linkOpt.get().getAid();

        Optional<CorrelatedNotification> ref = correlator.awaitCorrelated(REMOTESIGN_REF_ROUTES, walletAid,
                ceremony.getRequestExnSaid(), properties.remotesignTimeout());
        if (ref.isEmpty()) {
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                    KeriAttestationProblems.KERI_WALLET_TIMEOUT,
                    "Timed out waiting for the wallet's remotesign ref.");
            return;
        }

        resolveAndComplete(ceremonyId, generation, walletAid, ceremony.getMetadataDigest(),
                ceremony.getKelFloorSequence(), ref.get());
    }

    // --- shared: from a correlated ref, locate + verify the anchoring KEL event, then complete ---

    /**
     * From the correlated ref (not from "latest key state" alone, design §4.6 step 5), verified against
     * {@code floorSequence} (F5 fix — see {@link KeriAttestationCeremonyEntity#getKelFloorSequence()}):
     * <ol>
     *   <li>If the ref exn payload yields an explicit candidate ({@link #extractCandidate}), that
     *       candidate MUST resolve to a KEL event whose sequence is at or after {@code floorSequence}
     *       AND whose seal contains {@code metadataDigest}. If it doesn't — wrong event, too old, or
     *       simply not found — this fails immediately with {@code ATTEST_SEAL_MISMATCH}; there is no
     *       fallback shopping for a different event once an explicit candidate is on the table.</li>
     *   <li>If the ref exn payload yields no candidate at all, this falls back to a <b>bounded</b> scan
     *       of {@code ixn} events between {@code floorSequence} and the wallet's current key-state
     *       sequence (queried via {@link #queryLatestSequenceWithRetries}), looking for one whose seal
     *       contains {@code metadataDigest}. Unlike the removed unconditional "accept the latest ixn
     *       event" path, an event outside that bounded window — including one older than the floor —
     *       can never satisfy the request, regardless of its seal.</li>
     * </ol>
     * The exact ref-exn-payload shape (which yields candidate 1) isn't confirmed against a live wallet
     * yet (Task 8); both paths are unit-tested independently.
     */
    private void resolveAndComplete(String ceremonyId, int generation, String walletAid, String metadataDigest,
            String floorSequence, CorrelatedNotification ref) {
        try {
            AnchorCandidate candidate = extractCandidate(ref.exn());
            List<Map<String, Object>> kel = fetchKel(walletAid);
            List<Map<String, Object>> ixnEvents = kel.stream().filter(ke -> "ixn".equals(ke.get("t"))).toList();

            Map<String, Object> event;
            if (!candidate.isEmpty()) {
                event = locateExplicitCandidate(ixnEvents, candidate);
                if (event == null || !satisfiesFloorAndDigest(event, floorSequence, metadataDigest)) {
                    ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                            KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                            "The wallet ref's explicit anchoring-event candidate did not verify (not found, "
                                    + "sequence before the floor, or seal does not contain digest %s)."
                                            .formatted(metadataDigest));
                    return;
                }
            } else {
                Optional<String> currentSequence = queryLatestSequenceWithRetries(walletAid);
                event = currentSequence
                        .map(cs -> scanForSealMatch(ixnEvents, floorSequence, cs, metadataDigest))
                        .orElse(null);
                if (event == null) {
                    ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                            KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                            "No interaction (ixn) event between the floor sequence and the wallet's current "
                                    + "key state matched digest %s.".formatted(metadataDigest));
                    return;
                }
            }

            String sequence = String.valueOf(event.get("s"));
            String eventSaid = String.valueOf(event.get("d"));
            boolean completed = ceremonyService.completeStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                    CeremonyState.ATTEST_ANCHORED, c -> {
                        c.setKelSequence(sequence);
                        c.setKelEventSaid(eventSaid);
                    });
            if (completed) {
                // Only claim the notification for the attempt the CAS actually accepted — a stale
                // (superseded) generation's markAndDelete would delete a signal a concurrent, winning
                // retry pre-check or awaitAnchor still needs to find.
                correlator.markAndDelete(ref.notificationId());
            }
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.warn("Failed to resolve ATTEST anchor for ceremony {}: {}", ceremonyId, e.getMessage());
            ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED,
                    KeriAttestationProblems.ATTEST_SEAL_MISMATCH,
                    "Error verifying the wallet's anchor: " + e.getMessage());
        }
    }

    // --- candidate extraction from the ref exn payload (design §4.6 step 5) ---

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

    // --- key-state fallback: bounded retries confirming KEL availability, design §4.6 step 5 ---

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
    //     shapes (Task 8 spike's typed KeyEventRecord/KeyEvent aren't available at this jar version;
    //     see docs/keri/spike/RemotesignAnchorSpike.java for the confirmed field names to expect) ---

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
     *  then by sequence (F5 fix: no fallback to "the latest event" if neither matches — a caller with an
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

    /** Bounded scan (F5 fix) over {@code ixnEvents} whose sequence falls within
     *  [{@code floorSequence}, {@code currentSequence}] (hex-compared numerically), returning the first
     *  whose seal contains {@code digestQb64} — or {@code null} if none in that window match. Replaces
     *  the old unconditional "accept the latest ixn event" fallback: an event outside the window can
     *  never satisfy the request regardless of its seal, and a {@code null} floor is treated as no lower
     *  bound (only ceremonies created before this column existed should ever have one). */
    private static Map<String, Object> scanForSealMatch(List<Map<String, Object>> ixnEvents, String floorSequence,
            String currentSequence, String digestQb64) {
        long floor = floorSequence != null ? parseHexSequence(floorSequence) : 0L;
        long current = parseHexSequence(currentSequence);
        for (Map<String, Object> ke : ixnEvents) {
            long sn = parseHexSequence(String.valueOf(ke.get("s")));
            if (sn < floor || sn > current) {
                continue;
            }
            if (sealContainsDigest(ke.get("a"), digestQb64)) {
                return ke;
            }
        }
        return null;
    }

    /** {@code event}'s sequence must be at or after {@code floorSequence} (hex-compared numerically; a
     *  {@code null} floor imposes no lower bound) AND its seal must contain {@code digestQb64} (F5 fix).
     */
    private static boolean satisfiesFloorAndDigest(Map<String, Object> event, String floorSequence,
            String digestQb64) {
        if (floorSequence != null) {
            long sn = parseHexSequence(String.valueOf(event.get("s")));
            if (sn < parseHexSequence(floorSequence)) {
                return false;
            }
        }
        return sealContainsDigest(event.get("a"), digestQb64);
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

    private Either<ProblemDetail, Void> failAttest(String ceremonyId, int generation, String title, String detail) {
        ceremonyService.failStep(ceremonyId, generation, CeremonyState.ATTEST_REQUESTED, title, detail);
        return Either.left(KeriAttestationProblems.unprocessable(title, detail));
    }

    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /** F2 fix: a sync-path guarded update ({@link CeremonyService#updateWaitingStepData}) found the
     *  ceremony no longer waiting on {@code ATTEST_REQUESTED} — a concurrent retry/sweep transition beat
     *  this attempt to it. Reported the same way any other stale-state conflict is. */
    private static ProblemDetail staleCeremonyProblem(String ceremonyId) {
        return KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE,
                "Ceremony %s is no longer waiting on the expected step.".formatted(ceremonyId));
    }
}
