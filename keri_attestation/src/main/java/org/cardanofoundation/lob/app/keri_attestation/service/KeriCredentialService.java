package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CeremonyView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriNotificationCorrelator.CorrelatedNotification;
import org.cardanofoundation.signify.app.Exchanging.ExchangeMessageResult;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.core.States.HabState;
import org.cardanofoundation.signify.core.States.State;

/**
 * Drives IPEX credential presentation SYNCHRONOUSLY, in the request thread: the
 * platform's agent AID requests a credential from the user's linked wallet AID (apply), waits in-thread
 * for the wallet's offer, agrees, waits in-thread for the grant, admits, then fetches and validates the
 * full CESR chain before persisting it to the identity link and returning the ceremony's final state.
 *
 * <p><b>Why synchronous:</b> the previous async model
 * (a quick synchronous "send the apply" half returning 202, followed by a background-executor
 * continuation that awaited the wallet's replies) was found, under live wallet testing, to never observe
 * the wallet's offer/grant notifications reliably — the split introduced a race that a single in-thread
 * round trip simply doesn't have. This class now does the whole apply→offer→agree→grant→admit exchange
 * on the original request thread with no handoff at all: {@link #presentCredential} is the ONLY entry
 * point, and it blocks the calling thread for the whole exchange (bounded by
 * {@link KeriAttestationProperties#remotesignTimeout()} per wait). There is no longer a background
 * runner to hand off to, and no cross-request "resume mid-step" phase to persist: a crash mid-flight
 * simply abandons this one HTTP request, and the ceremony sits at {@code CREDENTIAL_REQUESTED} for a
 * subsequent retry (or the cleanup sweep) to fail out — exactly the recovery story a single-threaded
 * flow already has.
 *
 * <p><b>Live-testing fix:</b> {@link #ensureSchemasResolved} resolves every configured schema SAID as
 * an OOBI on OUR OWN agent before the first apply that references it is ever sent — KERIA silently
 * drops an IPEX exchange referencing a schema SAID the receiving agent has never itself resolved, so
 * without this a real wallet's "present" action does nothing observable (no error, no notification).
 * See that method's javadoc for the full rationale.
 *
 * <p><b>Dual-path presentation:</b> the apply→offer→agree→
 * grant→admit negotiation above is the negotiated contract, but a real Veridian wallet build was
 * observed, on the actual backend log, to present via a <em>spontaneous</em> IPEX grant instead —
 * apply→grant→admit, with NO offer and NO agree ever sent at all; the notification queue after a live
 * "present" tap contained only {@code /exn/ipex/grant} entries, zero {@code /exn/ipex/offer}. {@link
 * #presentCredential} therefore waits for the FIRST unread notification on EITHER an offer or a grant
 * route and branches on which one actually arrived: a grant skips the offer/agree steps entirely and
 * admits directly (using the admit's own {@code atc} — there is no agree to borrow one from); an offer
 * falls through to the original negotiation, unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriCredentialService {

    // KERIA surfaces an inbound IPEX exn's route on the notification as EITHER the "/exn/"-prefixed
    // form or the bare form, non-deterministically. Matching only the "/exn/" form silently drops the
    // wallet's offer/grant notification, so the credential step hangs even though the wallet responded.
    // Accept both forms.
    private static final List<String> OFFER_ROUTES = List.of("/exn/ipex/offer", "/ipex/offer");
    private static final List<String> GRANT_ROUTES = List.of("/exn/ipex/grant", "/ipex/grant");

    /** Dual-path presentation: the
     *  initial post-apply wait (and the retry pre-check) must watch for EITHER an offer or a spontaneous
     *  grant, since a real Veridian build was observed to send the grant directly with no offer at all.
     *  {@link #isGrantRoute} then tells the two apart on the notification that actually arrives. */
    private static final List<String> OFFER_OR_GRANT_ROUTES =
            List.of("/exn/ipex/offer", "/ipex/offer", "/exn/ipex/grant", "/ipex/grant");

    /** The {@code KERI_DATETIME} pattern: passed explicitly to
     *  every exn this class builds rather than relying on the pinned signify jar's own
     *  null-datetime fallback ({@code Exchanging.exchange}), which derives its timestamp from
     *  {@code java.util.Date} and can render withOUT a fractional-seconds separator at all
     *  (e.g. {@code "...T00:00:00000+00:00"}, missing the {@code "."}) on the rare timestamp that lands
     *  on an exact whole second — a malformed {@code dt} a strict wallet-side schema check could reject
     *  outright. This formatter always renders exactly six fractional digits. */
    private static final DateTimeFormatter KERI_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");

    /** Short wait before re-sending, to catch a late-arriving matching notification. Mirrors
     *  {@link KeriAttestService}'s retry pre-check, applied to the credential-presentation step. */
    private static final Duration RETRY_PRECHECK_TIMEOUT = Duration.ofSeconds(2);

    /** Bounded wait for a single schema-OOBI resolve ({@link #ensureSchemasResolved}) — mirrors
     *  {@code KeriOobiService#RESOLVE_TIMEOUT_MILLIS}'s own bound on the same underlying
     *  {@code operations().wait} call, applied here to a schema OOBI instead of a wallet's identity OOBI. */
    private static final long SCHEMA_RESOLVE_TIMEOUT_MILLIS = 15_000L;

    private final KeriAttestationClient client;
    private final KeriAgentService agentService;
    private final KeriNotificationCorrelator correlator;
    private final CredentialChainValidator validator;
    private final CeremonyService ceremonyService;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationProperties properties;
    private final KeriOobiService oobiService;

    /** In-memory cache of schema SAIDs already resolved as an OOBI on our agent this process
     *  ({@link #ensureSchemasResolved}) — a schema, once resolved, stays resolved for the life of the
     *  agent, so this avoids re-resolving (and re-waiting on) the same SAID on every presentation. */
    private final Set<String> resolvedSchemaSaids = ConcurrentHashMap.newKeySet();

    /**
     * Orchestrates the CREDENTIAL_REQUEST step end-to-end, SYNCHRONOUSLY, for a single controller call
     *: {@link #ensureSchemasResolved} → {@link CeremonyService#beginStep} from {@code
     * OOBI_RESOLVED} to {@code CREDENTIAL_REQUESTED} → a short retry pre-check for a reply that already
     * arrived on a previous attempt's apply → apply → wait, in-thread, for EITHER an offer or a
     * spontaneous grant (dual-path, class javadoc) → branch: a grant admits directly; an offer falls
     * through to the negotiated flow (agree → wait for the grant, in-thread → admit) → fetch the full
     * CESR chain → {@link CredentialChainValidator} → persist + complete the step. Every wire step logs
     * at INFO so a stalled live run shows exactly where it stopped.
     *
     * @return {@link Either#right} with the ceremony's final view once the step completes (or, per the
     *         retry pre-check, resumes and completes from a late-arriving offer or grant); {@link
     *         Either#left} with the problem on any failure — the ceremony itself is always left in a
     *         terminal ({@code FAILED}) or successfully-advanced ({@code CREDENTIAL_RECEIVED}) state
     *         before this method returns, never stuck at {@code CREDENTIAL_REQUESTED}.
     */
    public Either<ProblemDetail, CeremonyView> presentCredential(String ceremonyId, String userId, boolean retry) {
        // Live-testing fix: resolved BEFORE beginStep, i.e. before any ceremony state is touched at
        // all — a resolution failure here must surface as a plain problem, not a failed/rolled-back
        // ceremony step. See ensureSchemasResolved's javadoc for why this has to happen at all.
        Either<ProblemDetail, Void> schemasResolved =
                ensureSchemasResolved(properties.credentialPolicy().schemaSaids());
        if (schemasResolved.isLeft()) {
            return Either.left(schemasResolved.getLeft());
        }

        Either<ProblemDetail, KeriAttestationCeremonyEntity> begun = ceremonyService.beginStep(ceremonyId, userId,
                CeremonyState.OOBI_RESOLVED, CeremonyState.CREDENTIAL_REQUESTED, retry);
        if (begun.isLeft()) {
            return Either.left(begun.getLeft());
        }
        KeriAttestationCeremonyEntity ceremony = begun.get();
        int generation = ceremony.getAttemptGeneration();

        Optional<KeriIdentityLinkEntity> linkOpt = identityLinkRepository.findById(userId);
        if (linkOpt.isEmpty() || linkOpt.get().getAid() == null) {
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.IDENTITY_NOT_LINKED,
                    "User %s has no linked identity to request a credential presentation from.".formatted(userId));
        }
        String linkedAid = linkOpt.get().getAid();
        String agentName = agentService.agentName();

        // Re-resolve the wallet's OOBI on our agent before presenting: a contact resolved once at
        // pairing can go stale, and re-resolving refreshes the wallet's key state / endpoints (and the
        // agent's mailbox relationship to it) so this ceremony's apply is both deliverable to the wallet
        // and — the reason it's here — able to receive the wallet's reply back. Best-effort: a refresh
        // failure does not block a presentation that may still succeed on the existing contact, so it is
        // logged and swallowed rather than failing the step. (No-op when the link has no stored OOBI.)
        String walletOobiUrl = linkOpt.get().getOobiUrl();
        if (walletOobiUrl != null && !walletOobiUrl.isBlank()) {
            log.info("re-resolving wallet OOBI before presentation (aid {})", linkedAid);
            Either<ProblemDetail, Void> refreshed = oobiService.refreshResolve(userId, walletOobiUrl, linkedAid);
            if (refreshed.isLeft()) {
                log.warn("wallet OOBI re-resolve failed (proceeding best-effort on the existing contact): {}",
                        refreshed.getLeft().getDetail());
            } else {
                log.info("wallet OOBI re-resolved");
            }
        }

        // Cross-KERIA delivery investigation: the wallet (on a DIFFERENT, by-design KERIA) presents and
        // shows success, but the resulting grant never appears in notifications().list() here — a
        // delivery/topology question, not credential logic. Dumped once, up front, before anything else
        // in this attempt touches the wire, so a stalled live run always has this on hand regardless of
        // where the flow subsequently gets stuck.
        logReceiveDiagnostics(linkedAid, agentName);

        // Retry pre-check: before sending a fresh IPEX
        // apply, look for a late-arriving offer OR grant left over from a previous attempt. Dual-path
        //: the real wallet was observed to present
        // via a spontaneous grant with no offer at all, so this pre-check — like the wait below — must
        // watch for either. Route-only, like every claim in this module — see
        // KeriNotificationCorrelator#awaitByRoute's javadoc. Found: resume straight into the matching
        // continuation below without resending the apply. Not found (or this is the first attempt,
        // requestExnSaid still null): fall through and build + send a fresh apply.
        CorrelatedNotification claimedNotification = null;
        if (retry && ceremony.getRequestExnSaid() != null) {
            claimedNotification = correlator.awaitByRoute(OFFER_OR_GRANT_ROUTES, RETRY_PRECHECK_TIMEOUT).orElse(null);
        }

        if (claimedNotification == null) {
            Either<ProblemDetail, Void> sent = sendApply(ceremony, linkedAid, agentName);
            if (sent.isLeft()) {
                ProblemDetail problem = sent.getLeft();
                failCredentialStep(ceremonyId, generation, problem.getTitle(), problem.getDetail());
                return Either.left(problem);
            }

            log.info("waiting for offer or grant (routes {})", OFFER_OR_GRANT_ROUTES);
            Optional<CorrelatedNotification> claimed =
                    correlator.awaitByRoute(OFFER_OR_GRANT_ROUTES, properties.remotesignTimeout());
            if (claimed.isEmpty()) {
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.KERI_WALLET_TIMEOUT,
                        "Timed out waiting for /exn/ipex/offer or /exn/ipex/grant.");
            }
            claimedNotification = claimed.get();
        }

        // Dual-path branch: tell a spontaneous
        // grant apart from a negotiated offer by the CLAIMED notification's own route — see
        // isGrantRoute's javadoc for exactly how that route is determined.
        String credentialSaid;
        // Set by BOTH branches below to the grant notification's own ID, then only ever marked/deleted
        // at the very bottom of this method, once the credential is durably persisted — see that block's
        // comment for why. (Deliberately NOT deleted right after admit in the direct-grant branch either,
        // even though nothing else about that branch depends on the wallet for the rest of the flow:
        // credentialSaid itself lives only in this method's local state until completeStep commits it, so
        // an early delete here would leave a crash between a successful admit and a successful persist
        // with no durable trace of which credential was just admitted — exactly the failure mode
        // KeriNotificationCorrelator#markAndDelete's own contract exists to prevent, and exactly why the
        // negotiated path below has always deferred its own delete the same way.)
        String deferredGrantNotificationId = null;

        if (isGrantRoute(claimedNotification)) {
            log.info("grant received directly (spontaneous presentation), admitting {}",
                    claimedNotification.exnSaid());
            logGrantWireData(claimedNotification.exn());
            String directCredentialSaid = extractCredentialSaid(claimedNotification.exn());
            if (directCredentialSaid == null) {
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "IPEX grant exchange did not embed an ACDC (e.acdc.d missing).");
            }

            try {
                ExchangeMessageResult admitResult = client.client().ipex().admit(IpexAdmitArgs.builder()
                        .senderName(agentName).recipient(linkedAid).message("")
                        .grantSaid(claimedNotification.exnSaid()).datetime(nowKeriTimestamp()).build());
                // submitAdmit is given the ADMIT's own atc, not an agree's: this branch has no
                // preceding agree to borrow one from.
                logAdmitExn(admitResult, claimedNotification.exnSaid(), linkedAid, "admit-own");
                Object admitOp = client.client().ipex().submitAdmit(agentName, admitResult.exn(), admitResult.sigs(),
                        admitResult.atc(), List.of(linkedAid));
                client.client().operations().wait(Operation.fromObject(admitOp));
                log.info("admit operation completed");
            } catch (Exception e) {
                interruptIfNeeded(e);
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Failed to admit IPEX grant: " + e.getMessage());
            }
            log.info("admit sent");
            credentialSaid = directCredentialSaid;
            deferredGrantNotificationId = claimedNotification.notificationId();
        } else {
            log.info("offer received {}", claimedNotification.exnSaid());
            // The offer notification is claimed (marked + deleted) immediately once its SAID has been
            // read, before the agree is even built.
            correlator.markAndDelete(claimedNotification.notificationId());

            ExchangeMessageResult agreeResult;
            try {
                agreeResult = client.client().ipex().agree(IpexAgreeArgs.builder()
                        .senderName(agentName).recipient(linkedAid).message("")
                        .offerSaid(claimedNotification.exnSaid()).datetime(nowKeriTimestamp()).build());
                Object agreeOp = client.client().ipex().submitAgree(agentName, agreeResult.exn(), agreeResult.sigs(),
                        List.of(linkedAid));
                client.client().operations().wait(Operation.fromObject(agreeOp));
            } catch (Exception e) {
                interruptIfNeeded(e);
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Failed to send IPEX agree: " + e.getMessage());
            }
            log.info("agree sent");

            log.info("waiting for grant (routes {})", GRANT_ROUTES);
            Optional<CorrelatedNotification> grant =
                    correlator.awaitByRoute(GRANT_ROUTES, properties.remotesignTimeout());
            if (grant.isEmpty()) {
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.KERI_WALLET_TIMEOUT,
                        "Timed out waiting for /exn/ipex/grant.");
            }
            CorrelatedNotification grantNotification = grant.get();
            log.info("grant received {}", grantNotification.exnSaid());
            logGrantWireData(grantNotification.exn());

            String negotiatedCredentialSaid = extractCredentialSaid(grantNotification.exn());
            if (negotiatedCredentialSaid == null) {
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "IPEX grant exchange did not embed an ACDC (e.acdc.d missing).");
            }

            try {
                ExchangeMessageResult admitResult = client.client().ipex().admit(IpexAdmitArgs.builder()
                        .senderName(agentName).recipient(linkedAid).message("")
                        .grantSaid(grantNotification.exnSaid()).datetime(nowKeriTimestamp()).build());
                // submitAdmit is given the AGREE exchange's own atc, NOT the admit's own — a proven
                // wallet-contract quirk this module matches.
                logAdmitExn(admitResult, grantNotification.exnSaid(), linkedAid, "agree");
                Object admitOp = client.client().ipex().submitAdmit(agentName, admitResult.exn(), admitResult.sigs(),
                        agreeResult.atc(), List.of(linkedAid));
                client.client().operations().wait(Operation.fromObject(admitOp));
                log.info("admit operation completed");
            } catch (Exception e) {
                interruptIfNeeded(e);
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Failed to admit IPEX grant: " + e.getMessage());
            }
            log.info("admit sent");
            credentialSaid = negotiatedCredentialSaid;
            deferredGrantNotificationId = grantNotification.notificationId();
        }

        String fullCesr;
        try {
            log.info("fetching credential CESR chain for {}", credentialSaid);
            Optional<String> cesrOpt = client.client().credentials().get(credentialSaid);
            if (cesrOpt.isEmpty()) {
                log.warn("credential {} not retrievable from agent after admit", credentialSaid);
                return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                        "Credential %s was not found in the store after admit.".formatted(credentialSaid));
            }
            fullCesr = cesrOpt.get();
            log.info("credential CESR chain fetched ({} chars)", fullCesr.length());
        } catch (Exception e) {
            interruptIfNeeded(e);
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED,
                    "Failed to fetch credential %s: %s".formatted(credentialSaid, e.getMessage()));
        }

        // This is the only external-boundary call in this method not backed by a checked-exception
        // contract, so it's easy to forget it can still throw (e.g. a malformed/hostile chain tripping
        // an assumption CredentialChainValidator didn't explicitly guard) — wrapped the same as every
        // other step so a defect here fails the ceremony instead of escaping this request thread.
        List<String> allowedSchemaSaids = properties.credentialPolicy().schemaSaids();
        List<String> trustedRootAids = properties.credentialPolicy().trustedRootAids();
        log.info("validating credential chain (issuee={}, allowed schemas={}, trusted roots={})", linkedAid,
                allowedSchemaSaids, trustedRootAids);
        Either<ProblemDetail, ValidatedCredential> validated;
        try {
            validated = validator.validate(fullCesr, linkedAid, allowedSchemaSaids, trustedRootAids);
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.warn("credential chain validation threw: {}", e.getMessage(), e);
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REJECTED,
                    "Chain validation error: " + e.getMessage());
        }
        if (validated.isLeft()) {
            log.warn("credential chain validation rejected: {}", validated.getLeft().getDetail());
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REJECTED,
                    validated.getLeft().getDetail());
        }
        ValidatedCredential vc = validated.get();

        // Defense-in-depth: the validator finds its leaf by issuee match, independently of the SAID we
        // fetched the stream for — they must agree. A mismatch would mean the presented stream's
        // issuee-matching leaf isn't the credential the grant/admit round trip was actually about,
        // which should be structurally impossible but is cheap to assert outright rather than trust.
        if (!credentialSaid.equals(vc.credentialSaid())) {
            log.warn("validated leaf credential {} does not match the fetched credential {}", vc.credentialSaid(),
                    credentialSaid);
            return failCredentialRequest(ceremonyId, generation, KeriAttestationProblems.CREDENTIAL_REJECTED,
                    "Validated leaf credential %s does not match the fetched credential %s."
                            .formatted(vc.credentialSaid(), credentialSaid));
        }
        log.info("credential validated {}", vc.credentialSaid());

        // Fold the link write into completeStep's mutator (mirrors
        // KeriAuthBeginService#persistAuthBeginIfIdentityStillCurrent exactly): the credential write and
        // the ceremony's CREDENTIAL_REQUESTED -> CREDENTIAL_RECEIVED transition commit atomically, in
        // CeremonyService's one transaction.
        String finalCredentialSaid = vc.credentialSaid();
        String finalSchemaSaid = vc.schemaSaid();
        int bindingVersion = ceremony.getBindingVersion();
        boolean completed = ceremonyService.completeStep(ceremonyId, generation, CeremonyState.CREDENTIAL_REQUESTED,
                CeremonyState.CREDENTIAL_RECEIVED,
                c -> persistCredentialIfIdentityStillCurrent(userId, bindingVersion, finalCredentialSaid,
                        finalSchemaSaid));
        if (!completed) {
            // Stale CAS (a concurrent retry superseded this attempt) — the link must not be written
            // either (the mutator above never runs), and the grant notification must be left alone: the
            // winning attempt's own wait is (or was) matching against the same request.
            return Either.left(staleCeremonyProblem(ceremonyId));
        }
        log.info("credential step complete (schema {})", finalSchemaSaid);

        // Both branches above defer to here (see deferredGrantNotificationId's own comment): only after
        // both the link and the ceremony transition are durably committed — an earlier mark-and-delete
        // would let a crash between the two silently lose the wallet's reply, exactly the failure mode
        // KeriNotificationCorrelator#markAndDelete's contract exists to prevent.
        if (deferredGrantNotificationId != null) {
            correlator.markAndDelete(deferredGrantNotificationId);
        }

        return ceremonyService.get(ceremonyId, userId);
    }

    /**
     * Live-testing fix: resolves every one of {@code schemaSaids} as an OOBI on OUR OWN agent — not
     * just recognized by the wallet — before an IPEX apply referencing any of them is ever sent.
     *
     * <p>Discovered during real-wallet testing: presenting a credential from a real Veridian wallet did
     * nothing observable when clicked — no error surfaced anywhere, the apply reached the wallet fine,
     * but no notification for its reply ever arrived back on our side. Root cause: our KERIA agent had
     * never resolved the credential schema's OOBI itself. KERIA silently drops an IPEX exchange
     * referencing a schema SAID the receiving agent doesn't already know, so the wallet's offer — sent
     * in response to a perfectly valid apply — is dropped before it ever becomes a notification here.
     * The fix is to resolve every schema OOBI up front: for each schema,
     * {@code client.oobis().resolve(schemaBaseUrl + "/" + said, null)} followed by
     * {@code client.operations().wait(...)} — done here lazily, once per SAID per process, cached in
     * {@link #resolvedSchemaSaids}, rather than eagerly at startup.
     *
     * <p>Called at the very top of {@link #presentCredential}, before {@code beginStep} touches any
     * ceremony state at all: a resolution failure must surface as a plain problem, never a
     * failed/rolled-back ceremony step.
     */
    private Either<ProblemDetail, Void> ensureSchemasResolved(List<String> schemaSaids) {
        if (schemaSaids == null || schemaSaids.isEmpty()) {
            return Either.right(null);
        }
        String baseUrl = withoutTrailingSlash(properties.credentialPolicy().schemaBaseUrl());
        for (String said : schemaSaids) {
            if (resolvedSchemaSaids.contains(said)) {
                continue;
            }
            String schemaUrl = baseUrl + "/" + said;
            try {
                Object resolveResult = client.client().oobis().resolve(schemaUrl, null);
                Operations.WaitOptions waitOptions = Operations.WaitOptions.builder()
                        .abortSignal(Operations.AbortSignal.builder().timeout(SCHEMA_RESOLVE_TIMEOUT_MILLIS).build())
                        .build();
                client.client().operations().wait(Operation.fromObject(resolveResult), waitOptions);
                resolvedSchemaSaids.add(said);
            } catch (Exception e) {
                interruptIfNeeded(e);
                log.warn("Failed to resolve schema OOBI {} on the agent: {}", schemaUrl, e.getMessage());
                return Either.left(KeriAttestationProblems.serviceUnavailable(
                        KeriAttestationProblems.KERI_AGENT_UNAVAILABLE,
                        "Failed to resolve schema OOBI %s on the agent: %s".formatted(schemaUrl, e.getMessage())));
            }
        }
        return Either.right(null);
    }

    /** Builds and sends the IPEX apply, persisting {@code requestExnSaid} before the send completes
     *  (the SAID is deterministic from the built, not-yet-sent exn — same persist-before-send idiom
     *  {@link KeriAttestService} uses). {@code requestExnSaid} is read back by {@link #presentCredential}'s
     *  own retry pre-check on a LATER, separate HTTP call — it is not used to resume mid-step within a
     *  single synchronous call, since there is no such thing anymore. */
    private Either<ProblemDetail, Void> sendApply(KeriAttestationCeremonyEntity ceremony, String linkedAid,
            String agentName) {
        try {
            List<String> schemaSaids = properties.credentialPolicy().schemaSaids();
            if (schemaSaids == null || schemaSaids.isEmpty()) {
                return Either.left(requestFailed(
                        "No schema SAIDs configured under lob.keri-attestation.credential-policy.schema-saids."));
            }
            String schemaSaid = schemaSaids.get(0);

            Optional<HabState> senderOpt = client.client().identifiers().get(agentName);
            if (senderOpt.isEmpty()) {
                return Either.left(requestFailed(
                        "No local HabState found for agent identifier %s.".formatted(agentName)));
            }

            // Wallet contract: build /ipex/apply directly via
            // createExchangeMessage, with oobiUrl at the TOP level of the payload (exn.a.oobiUrl).
            // IpexApplyArgs#attributes (the old approach) lands under exn.a.a instead, which the
            // wallet never finds.
            //
            // Live-testing fix: oobiUrl must be the CREDENTIAL SCHEMA SERVER's base URL (configured as
            // lob.keri-attestation.credential-policy.schema-base-url), NOT our agent's own OOBI — this
            // is where a Veridian-style wallet actually resolves the schema behind the apply's SAID
            // from, with a trailing slash (proven working against a real wallet during live testing).
            Map<String, Object> applyData = new LinkedHashMap<>();
            applyData.put("m", "");
            applyData.put("s", schemaSaid);
            applyData.put("a", new LinkedHashMap<>());
            applyData.put("oobiUrl", withTrailingSlash(properties.credentialPolicy().schemaBaseUrl()));
            ExchangeMessageResult applyResult = client.client().exchanges().createExchangeMessage(
                    senderOpt.get(), "/ipex/apply", applyData, new LinkedHashMap<>(), linkedAid,
                    nowKeriTimestamp(), null);
            String exnSaid = (String) applyResult.exn().getKed().get("d");

            boolean persisted = ceremonyService.updateWaitingStepData(ceremony.getId(), ceremony.getAttemptGeneration(),
                    CeremonyState.CREDENTIAL_REQUESTED, c -> c.setRequestExnSaid(exnSaid));
            if (!persisted) {
                return Either.left(staleCeremonyProblem(ceremony.getId()));
            }

            // Every IPEX submit is followed by operations().wait, not just fire-and-forget.
            Object applyOp = client.client().ipex().submitApply(agentName, applyResult.exn(), applyResult.sigs(),
                    List.of(linkedAid));
            client.client().operations().wait(Operation.fromObject(applyOp));
            log.info("IPEX apply sent to {}", linkedAid);
            return Either.right(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Either.left(requestFailed("Interrupted while sending IPEX apply: " + e.getMessage()));
        } catch (Exception e) {
            log.warn("Failed to send IPEX apply for ceremony {}: {}", ceremony.getId(), e.getMessage());
            return Either.left(requestFailed("Failed to send IPEX apply: " + e.getMessage()));
        }
    }

    /**
     * One-time "receive-side diagnostic" dump (cross-KERIA delivery investigation): the backend sends
     * the IPEX apply and the wallet (on a DIFFERENT, by-design KERIA) presents and shows success, but
     * the resulting grant never surfaces in {@code notifications().list()} here — a delivery/topology
     * question, not credential logic. Logs every piece of receive-side state a live run needs to
     * diagnose that: our own agent's identity and OOBI; our agent's own witness config (confirming the
     * witness-less AID — see {@link org.cardanofoundation.lob.app.keri_attestation.config.SignifyClientConfig
     * SignifyClientConfig}'s {@code createAid} — actually took effect, since a witnessed AID routes
     * inbound exchanges through witness mailboxes the wallet's KERIA can't reach); whether the wallet AID
     * is even a resolved contact our agent could address a reply to; and the RAW notification store
     * (not just the parsed routes {@link KeriNotificationCorrelator} looks at), in case something is
     * being mis-parsed. Called once, at the very top of {@link #presentCredential}, before the apply is
     * ever (re)sent.
     *
     * <p>Diagnostics-only: wrapped in its own try/catch so any failure here (e.g. the agent being
     * temporarily unreachable for one of these calls) can never break the actual presentation flow.
     */
    private void logReceiveDiagnostics(String linkedAid, String agentName) {
        try {
            log.info("agent identity: name={} prefix={} oobi={}", agentService.agentName(), agentService.agentPrefix(),
                    agentService.agentOobi());

            Optional<HabState> agentHab = client.client().identifiers().get(agentName);
            if (agentHab.isPresent()) {
                State state = agentHab.get().getState();
                List<String> witnesses = state != null ? state.getB() : null;
                String toad = state != null ? state.getBt() : null;
                log.info("agent key-state: witnesses={} toad={}", witnesses, toad);
            } else {
                log.info("agent key-state: no HabState found for agent identifier {}", agentName);
            }

            // Told apart from the outer catch deliberately: a thrown/empty contact lookup is itself the
            // diagnostic signal ("our agent can't even address this AID"), not a diagnostics failure —
            // it must still log something actionable rather than falling through to the generic
            // "receive diagnostics unavailable" message below.
            try {
                Optional<Object> contact = client.client().contacts().get(linkedAid);
                if (contact.isPresent()) {
                    log.info("wallet contact {}: {}", linkedAid, contact.get());
                } else {
                    log.info("wallet AID {} is NOT a resolved contact (empty response) -- our agent may not "
                            + "be able to address it at all.", linkedAid);
                }
            } catch (Exception e) {
                interruptIfNeeded(e);
                log.info("wallet AID {} is NOT a resolved contact: {}", linkedAid, e.getMessage());
            }

            String rawNotes = client.client().notifications().list().notes();
            String truncatedNotes = rawNotes != null && rawNotes.length() > 2000
                    ? rawNotes.substring(0, 2000) + "...(truncated)"
                    : rawNotes;
            log.info("raw notifications: {}", truncatedNotes);
        } catch (Exception e) {
            interruptIfNeeded(e);
            log.info("receive diagnostics unavailable: {}", e.getMessage());
        }
    }

    // --- internals ---

    private Either<ProblemDetail, CeremonyView> failCredentialRequest(String ceremonyId, int generation, String title,
            String detail) {
        failCredentialStep(ceremonyId, generation, title, detail);
        return Either.left(KeriAttestationProblems.unprocessable(title, detail));
    }

    private void failCredentialStep(String ceremonyId, int expectedGeneration, String title, String detail) {
        ceremonyService.failStep(ceremonyId, expectedGeneration, CeremonyState.CREDENTIAL_REQUESTED, title, detail);
    }

    /**
     * Persists the validated credential to the identity link. <b>Only ever called from inside a
     * {@link CeremonyService#completeStep} mutator</b> — same rationale as
     * {@link KeriAuthBeginService#persistAuthBeginIfIdentityStillCurrent}'s javadoc: {@code
     * completeStep} only invokes its mutator after the ceremony row's own {@code (state,
     * attemptGeneration)} CAS has already confirmed this is the current, non-superseded attempt, so a
     * stale attempt's mutator never runs, and both the link write and the ceremony transition commit
     * together in {@code CeremonyService}'s one transaction. The re-fetch + bindingVersion re-check here
     * additionally guards the one race {@code completeStep}'s own CAS does not cover: a relink landing
     * mid-flight (a separate {@code KeriOobiService} write, out-of-band of the ceremony's own CAS
     * fields) must never let a stale write re-attach a credential to what is now a different identity —
     * the ceremony step itself still completes (the CAS on {@code (state, attemptGeneration)} has no
     * way to know about the relink); {@link CeremonyService#validateAndConsume}'s own bindingVersion
     * check is the final safety net at consumption time.
     *
     * <p>The re-fetch is row-locked via
     * {@link org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository#findByUserIdForUpdate}
     * rather than a plain {@code findById}: this write and {@code KeriOobiService}'s relink write race
     * the same row, and without the lock the two could interleave into a row that is a mix of the old
     * and new identity (e.g. a relinked {@code aid} with a credential that belongs to the old AID).
     */
    private void persistCredentialIfIdentityStillCurrent(String userId, int expectedBindingVersion,
            String credentialSaid, String credentialSchemaSaid) {
        identityLinkRepository.findByUserIdForUpdate(userId).ifPresent(freshLink -> {
            if (freshLink.getBindingVersion() != expectedBindingVersion) {
                log.warn("Skipping credential link write for user {}: identity was relinked (expected binding "
                        + "version {}, now {}).", userId, expectedBindingVersion, freshLink.getBindingVersion());
                return;
            }
            freshLink.setCredentialSaid(credentialSaid);
            freshLink.setCredentialSchemaSaid(credentialSchemaSaid);
            identityLinkRepository.save(freshLink);
        });
    }

    /**
     * Dual-path branch decision: {@code
     * notification} was claimed off the combined {@link #OFFER_OR_GRANT_ROUTES} wait, so it is either an
     * offer or a spontaneous grant — this tells the two apart.
     *
     * <p>Prefers the FETCHED exchange's own {@code r} field ({@code notification.exn().get("r")}) when
     * it is itself recognized as one of the offer/grant routes; falls back to the notification's own
     * claimed route ({@link CorrelatedNotification#claimedRoute()}) otherwise. A real wallet's exn should
     * always carry its own {@code r}, but {@link KeriNotificationCorrelator#awaitByRoute} never
     * validates it against the awaited routes, so this does not assume it is always present or
     * trustworthy; {@code claimedRoute} is guaranteed to be one
     * of {@link #OFFER_OR_GRANT_ROUTES} by construction (it is exactly what {@code awaitByRoute}'s own
     * pre-filter matched on), so it is always a safe fallback. {@code claimedRoute} can still itself be
     * {@code null} for a caller that never populates it (the 3-arg {@code CorrelatedNotification}
     * constructor's default) — treated as "not a grant" rather than risking an NPE against the {@code
     * List.of(...)}-backed route lists below, which reject a {@code null} query element outright.
     */
    private static boolean isGrantRoute(CorrelatedNotification notification) {
        Object exnRoute = notification.exn().get("r");
        String route = (exnRoute instanceof String s && (OFFER_ROUTES.contains(s) || GRANT_ROUTES.contains(s)))
                ? s
                : notification.claimedRoute();
        return route != null && GRANT_ROUTES.contains(route);
    }

    private static String extractCredentialSaid(Map<String, Object> grantExn) {
        Object e = grantExn.get("e");
        if (!(e instanceof Map<?, ?> em)) {
            return null;
        }
        Object acdc = em.get("acdc");
        if (!(acdc instanceof Map<?, ?> am)) {
            return null;
        }
        Object said = am.get("d");
        return said instanceof String s ? s : null;
    }

    /** Same {@code exn.e.acdc} embed as {@link #extractCredentialSaid}, reading the schema ({@code s})
     *  instead of the SAID ({@code d}). {@code null} when the grant carries no ACDC at all — same
     *  tolerance as {@link #extractCredentialSaid}, since the caller's own "did not embed an ACDC" check
     *  runs immediately after {@link #logGrantWireData} either way. */
    private static String extractAcdcSchemaSaid(Map<String, Object> grantExn) {
        Object e = grantExn.get("e");
        if (!(e instanceof Map<?, ?> em)) {
            return null;
        }
        Object acdc = em.get("acdc");
        if (!(acdc instanceof Map<?, ?> am)) {
            return null;
        }
        Object schemaSaid = am.get("s");
        return schemaSaid instanceof String s ? s : null;
    }

    /** Same {@code exn.e.acdc} embed as {@link #extractCredentialSaid}/{@link #extractAcdcSchemaSaid},
     *  reading the ACDC's issuee identity: {@code e.acdc.a.i} (the attribute block's own {@code i},
     *  where a real ACDC normally carries it) when present, else {@code e.acdc.i} directly. {@code null}
     *  when neither is present or the grant carries no ACDC at all — same tolerance as its siblings. */
    private static String extractAcdcIssuee(Map<String, Object> grantExn) {
        Object e = grantExn.get("e");
        if (!(e instanceof Map<?, ?> em)) {
            return null;
        }
        Object acdc = em.get("acdc");
        if (!(acdc instanceof Map<?, ?> am)) {
            return null;
        }
        Object a = am.get("a");
        if (a instanceof Map<?, ?> aMap && aMap.get("i") instanceof String s) {
            return s;
        }
        Object i = am.get("i");
        return i instanceof String s ? s : null;
    }

    /** Wire-diagnostics logging (revert-purge round: the previous fix here — eagerly purging "stale"
     *  unclaimed notifications right before every apply — turned out to delete the wallet's REAL grant
     *  reply, since it sat unread from the user's own presentation; that purge has been reverted (see
     *  the report). The next open question is why Veridian rejects our resulting admit, which needs the
     *  exact wire data to answer — so this logs the grant exn's key routing fields and its embedded
     *  ACDC identity the moment a grant is claimed, direct or negotiated (both branches call this),
     *  before it is ever admitted. */
    private static void logGrantWireData(Map<String, Object> grantExn) {
        log.info("grant exn: i={} r={} p={} rp={}", grantExn.get("i"), grantExn.get("r"), grantExn.get("p"),
                grantExn.get("rp"));
        log.info("grant acdc: d={} s={} i={}", extractCredentialSaid(grantExn), extractAcdcSchemaSaid(grantExn),
                extractAcdcIssuee(grantExn));
    }

    /** Wire-diagnostics logging (revert-purge round, same rationale as {@link #logGrantWireData}): logs
     *  the admit exn actually built, right before it is submitted, in both branches — {@code atc} is a
     *  descriptive label for WHICH atc submitAdmit is given (the admit's own, {@code "admit-own"}, in
     *  the direct-grant branch; the agree's, {@code "agree"}, in the negotiated branch — see each call
     *  site's own comment), not the raw atc bytes themselves. */
    private static void logAdmitExn(ExchangeMessageResult admitResult, String grantSaid, String recipient,
            String atc) {
        Map<String, Object> ked = admitResult.exn().getKed();
        log.info("admit exn: d={} p={} grantSaid={} recipient={} atc={}", ked.get("d"), ked.get("p"), grantSaid,
                recipient, atc);
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

    /** Normalizes {@code schemaBaseUrl} to end with exactly one trailing slash — the apply payload's
     *  {@code oobiUrl} (live-testing fix) needs one; accepts either input form (configured with or
     *  without one). */
    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /** Normalizes {@code schemaBaseUrl} to end with NO trailing slash — {@link #ensureSchemasResolved}
     *  builds each schema OOBI URL as {@code baseUrl + "/" + said}, which would double up the slash if
     *  {@code schemaBaseUrl} was itself configured with a trailing one. */
    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ProblemDetail requestFailed(String detail) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REQUEST_FAILED, detail);
    }

    /** The guarded update found the ceremony no longer waiting on the step it was called for: a
     *  concurrent retry or sweep transition beat this attempt to it. */
    private static ProblemDetail staleCeremonyProblem(String ceremonyId) {
        return KeriAttestationProblems.conflict(KeriAttestationProblems.CEREMONY_INVALID_STATE,
                "Ceremony %s is no longer waiting on the expected step.".formatted(ceremonyId));
    }
}
