/// usr/bin/env jbang "$0" "$@" ; exit $?
///
// @formatter:off
//JAVA 24+
//COMPILE_OPTIONS -source 24
//RUNTIME_OPTIONS

//REPOS snapshots=https://central.sonatype.com/repository/maven-snapshots/
//REPOS central=https://repo.maven.apache.org/maven2
//DEPS org.cardanofoundation:signify:0.1.2-5eb55c9-SNAPSHOT
// @formatter:on

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.aiding.CreateIdentifierArgs;
import org.cardanofoundation.signify.app.aiding.EventResult;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.Operations;
import org.cardanofoundation.signify.cesr.Diger;
import org.cardanofoundation.signify.cesr.Saider;
import org.cardanofoundation.signify.cesr.args.RawArgs;
import org.cardanofoundation.signify.generated.keria.model.CompletedOOBIOperation;
import org.cardanofoundation.signify.generated.keria.model.CompletedQueryOperation;
import org.cardanofoundation.signify.generated.keria.model.Exn;
import org.cardanofoundation.signify.generated.keria.model.ExchangeResource;
import org.cardanofoundation.signify.generated.keria.model.HabState;
import org.cardanofoundation.signify.generated.keria.model.KeyEvent;
import org.cardanofoundation.signify.generated.keria.model.KeyEventRecord;
import org.cardanofoundation.signify.generated.keria.model.KeyStateRecord;
import org.cardanofoundation.signify.generated.keria.model.Notification;
import org.cardanofoundation.signify.generated.keria.model.OOBI;
import org.cardanofoundation.signify.generated.keria.model.OOBIOperation;
import org.cardanofoundation.signify.generated.keria.model.QueryOperation;
import org.cardanofoundation.signify.generated.keria.model.Tier;

/**
 * Task 8 spike — Veridian remotesign anchoring (cf-reeve-platform, keri_attestation module).
 *
 * <p><b>Purpose.</b> Answers the hard-blocker question in
 * {@code docs/superpowers/specs/2026-07-21-keri-wallet-attestation-design.md} §4.4: when the
 * backend sends a {@code /remotesign/ixn/req} exchange to a Veridian wallet AID, does the wallet
 * anchor a caller-chosen digest as the interaction-event seal ({@code a: [{d: <digest>}]}), or
 * only the SAID of the request envelope? CIP-170 requires {@code 170.d} to equal the direct
 * digest of the label-1447 metadata, which must equal the KEL seal the wallet anchors. If no KED
 * variant below makes the wallet anchor our digest, that is a hard blocker to escalate before
 * implementing Task 9 — see §4.4: "a transport-envelope SAID is not interchangeable with the CIP
 * digest, and we would need wallet-side or spec-side coordination before proceeding."
 *
 * <p><b>This is a MANUAL, human-operated spike.</b> It requires a real Veridian wallet to approve
 * the signing request interactively. Nothing here is automated end-to-end.
 *
 * <p><b>What it does:</b>
 * <ol>
 *   <li>Connects a signify client to the Reeve KERIA agent (creating/reusing a local passcode).</li>
 *   <li>Ensures a local identifier exists, resolves the operator's Veridian wallet OOBI, and
 *       extracts the wallet's AID.</li>
 *   <li>Computes a CESR Blake3-256 digest (qb64) over the fixed UTF-8 string
 *       {@code "reeve-remotesign-spike-v1"} — this is the "caller-chosen digest" under test.</li>
 *   <li>Sends {@code /remotesign/ixn/req} to the wallet AID with a KED shaped per
 *       {@code KED_VARIANT} (A, B, or C — see below) and prints the sent exn's SAID
 *       ({@code request_exn_said}).</li>
 *   <li>Polls notifications (3 minutes, 1.5s interval) for a {@code /remotesign/ixn/ref} (or
 *       {@code /exn/remotesign/ixn/ref}) reply and prints the FULL raw ref exn JSON — this is how
 *       we learn the live thread-back/correlation field for Task 6's {@code KeriNotificationCorrelator}.</li>
 *   <li>Queries the wallet's key state (bounded retries, best-effort — per spec §4.6 item 5, this
 *       only confirms KEL availability), then fetches the wallet's KEL directly and prints the
 *       anchoring event's sequence number, SAID, and full {@code a} (seal) list.</li>
 *   <li>Prints an explicit verdict line: {@code VERDICT SEAL_MATCHES_DIGEST: true/false}.</li>
 * </ol>
 *
 * <p><b>KED variants under test</b> (env {@code KED_VARIANT}, default {@code A}):
 * <ul>
 *   <li><b>A</b> — {@code {"d": "<digest qb64>"}}: a bare seal map, mirroring
 *       {@code client.identifiers().interact(name, seal)} semantics
 *       (see {@code docs/keri/AttestTransaction.java}) but sent to the wallet instead of applied
 *       to our own identifier.</li>
 *   <li><b>B</b> — {@code {"i": "<walletAid>", "d": "<digest qb64>"}}: adds the wallet's own AID
 *       alongside the digest.</li>
 *   <li><b>C</b> — a saidified envelope: build {@code {"i": "<walletAid>", "d": "", "digest":
 *       "<digest qb64>"}} then run it through {@code Saider.saidify(...)}, which overwrites
 *       {@code d} with the SAID of that map (i.e. a self-referential SAID, NOT our raw digest).
 *       This variant tells us whether the wallet anchors "whatever ends up in {@code d}" rather
 *       than a value we control directly.</li>
 * </ul>
 *
 * <p>If variant A does not produce a matching seal, re-run with {@code KED_VARIANT=B}, then
 * {@code C}. The script prints exact re-run instructions at the end of every run. If none of the
 * three variants produce {@code SEAL_MATCHES_DIGEST: true}, STOP — this is the spec §4.4 hard
 * blocker; report it before implementing Task 9.
 *
 * <p><b>Environment variables:</b>
 * <ul>
 *   <li>{@code KERI_URL} — KERIA agent URL. Default: the Reeve KERIA endpoint
 *       (see {@code docs/keri/advanced/PublishExistingCredential.java:64}).</li>
 *   <li>{@code KERI_BOOT_URL} — KERIA boot URL. Default: the Reeve KERIA boot endpoint
 *       (see {@code docs/keri/advanced/PublishExistingCredential.java:65}).</li>
 *   <li>{@code PASSCODE} — signify client passcode (bran). Optional: if omitted, a fresh one is
 *       generated and printed so you can reuse it (and thus the same local identifier) on
 *       subsequent runs.</li>
 *   <li>{@code IDENTIFIER_NAME} — local identifier alias. Default: {@code RemotesignSpike}.</li>
 *   <li>{@code WALLET_OOBI} — the Veridian wallet's OOBI URL. <b>Required.</b> In the Veridian
 *       app: Identifiers → select your AID → "Share" / "Connect" → the OOBI URL or QR code shown
 *       there (scan the QR with any QR reader, or use the app's "copy OOBI" action if present).</li>
 *   <li>{@code KED_VARIANT} — {@code A}, {@code B}, or {@code C} (see above). Default: {@code A}.</li>
 * </ul>
 *
 * <p><b>Run:</b>
 * <pre>
 *   WALLET_OOBI="http://&lt;host&gt;/oobi/&lt;walletAid&gt;/agent/&lt;agentAid&gt;" \
 *     jbang docs/keri/spike/RemotesignAnchorSpike.java
 * </pre>
 */
public class RemotesignAnchorSpike {

    // ------- Environment / configuration ------- //

    private static final String KERI_URL =
            System.getenv().getOrDefault("KERI_URL", "https://keria.cardano-foundation.app.reeve.technology");
    private static final String KERI_BOOT_URL =
            System.getenv().getOrDefault("KERI_BOOT_URL", "https://keria-boot.cardano-foundation.app.reeve.technology");
    private static final String PASSCODE = System.getenv().getOrDefault("PASSCODE", "");
    private static final String IDENTIFIER_NAME = System.getenv().getOrDefault("IDENTIFIER_NAME", "RemotesignSpike");
    private static final String WALLET_OOBI = System.getenv().getOrDefault("WALLET_OOBI", "");
    private static final String KED_VARIANT = System.getenv().getOrDefault("KED_VARIANT", "A").trim().toUpperCase();

    // ------- Spike constants ------- //

    private static final String TEST_BYTES_SEED = "reeve-remotesign-spike-v1";
    private static final String REMOTESIGN_TOPIC = "remotesign";
    private static final String REMOTESIGN_REQUEST_ROUTE = "/remotesign/ixn/req";
    private static final List<String> REMOTESIGN_REF_ROUTES =
            List.of("/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");
    private static final Duration NOTIFICATION_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration NOTIFICATION_POLL_INTERVAL = Duration.ofMillis(1500);
    private static final String WALLET_CONTACT_ALIAS = "remotesign-spike-wallet";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            // Top-level safety net: without this, any unchecked exception from the signify client
            // (SignifyAgentException, OperationTimeoutException, HeaderVerificationException, ...)
            // — e.g. a stale/mistyped WALLET_OOBI, or a dropped connection — prints a bare Java
            // stack trace instead of a message a human operator can act on.
            System.out.println();
            System.out.println("FATAL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) {
                System.out.println("Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        if (WALLET_OOBI.isBlank()) {
            System.err.println("ERROR: WALLET_OOBI environment variable is required "
                    + "(the Veridian wallet's OOBI URL — see the class Javadoc for where to find it).");
            System.exit(1);
            return;
        }
        if (!List.of("A", "B", "C").contains(KED_VARIANT)) {
            System.err.println("ERROR: KED_VARIANT must be one of A, B, C (got '" + KED_VARIANT + "').");
            System.exit(1);
            return;
        }

        banner("STEP 1 — connect signify client");
        SignifyClient client = connectClient();
        System.out.println("Connected to " + KERI_URL);
        System.out.println("Agent prefix: " + (client.getAgent() != null ? client.getAgent().getPre() : "<none>"));

        banner("STEP 2 — ensure local identifier, resolve wallet OOBI");
        HabState sender = ensureLocalIdentifier(client, IDENTIFIER_NAME);
        System.out.println("Local identifier '" + IDENTIFIER_NAME + "' AID: " + sender.getPrefix());

        Optional<OOBI> ownOobi = client.oobis().get(IDENTIFIER_NAME, "agent");
        ownOobi.ifPresentOrElse(
                o -> System.out.println("Our agent OOBI (pair this INTO Veridian first, if not done already): "
                        + o.getOobis()),
                () -> System.out.println("WARNING: could not fetch our own agent OOBI."));

        String walletAid = resolveWalletAid(client, WALLET_OOBI);
        System.out.println("Wallet AID: " + walletAid);

        banner("STEP 3 — compute digest over fixed test bytes");
        byte[] testBytes = TEST_BYTES_SEED.getBytes(StandardCharsets.UTF_8);
        String digestQb64 = new Diger(new RawArgs(), testBytes).getQb64();
        System.out.println("Test bytes (UTF-8): \"" + TEST_BYTES_SEED + "\"");
        System.out.println("Digest (qb64): " + digestQb64);

        banner("STEP 4 — build KED variant " + KED_VARIANT + " and send " + REMOTESIGN_REQUEST_ROUTE);
        Map<String, Object> ked = buildKed(KED_VARIANT, walletAid, digestQb64);
        System.out.println("KED payload sent as the exn's 'a' field:");
        System.out.println(pretty(ked));

        Exn sentExn = client.exchanges().send(
                IDENTIFIER_NAME, REMOTESIGN_TOPIC, sender, REMOTESIGN_REQUEST_ROUTE, ked, Map.of(), List.of(walletAid));
        String requestExnSaid = sentExn.getD();
        System.out.println();
        System.out.println("Sent. request_exn_said = " + requestExnSaid);
        System.out.println(">>> Now open Veridian and approve the signing request. <<<");

        banner("STEP 5 — poll notifications for the remotesign ref (up to "
                + NOTIFICATION_TIMEOUT.toMinutes() + " minutes)");
        Notification refNotification = waitForRefNotification(client);
        if (refNotification == null) {
            System.out.println("TIMEOUT: no " + REMOTESIGN_REF_ROUTES + " notification arrived within "
                    + NOTIFICATION_TIMEOUT.toMinutes() + " minutes.");
            System.out.println("(This could mean the request was never approved, was rejected, or the wallet"
                    + " uses a different notification route than assumed above — check Veridian directly.)");
            printVariantGuidance(KED_VARIANT, false);
            System.exit(1);
            return;
        }

        System.out.println("Notification received.");
        System.out.println("  route (a.r):        " + refNotification.getA().getR());
        System.out.println("  said  (a.d):         " + refNotification.getA().getD());
        System.out.println("  message (a.m):       " + refNotification.getA().getM());
        System.out.println("  extra fields:        " + refNotification.getA().getAdditionalProperties());
        System.out.println("Full notification JSON:");
        System.out.println(pretty(refNotification));

        Exn refExn = null;
        String candidateEventSaid = null;
        String refExnSaid = refNotification.getA() != null ? refNotification.getA().getD() : null;
        if (refExnSaid != null) {
            Optional<ExchangeResource> refResource = client.exchanges().get(refExnSaid);
            if (refResource.isPresent()) {
                System.out.println();
                System.out.println("Full ref exn (ExchangeResource) JSON — this is the live thread-back shape"
                        + " for Task 6's KeriNotificationCorrelator:");
                System.out.println(pretty(refResource.get()));
                refExn = refResource.get().getExn();
                candidateEventSaid = extractCandidateEventSaid(refExn);
                System.out.println("Candidate anchoring-event SAID read from the ref exn payload: "
                        + candidateEventSaid);
            } else {
                System.out.println("WARNING: could not fetch exn by said '" + refExnSaid + "' via /exchanges/{said}.");
            }
        } else {
            System.out.println("WARNING: notification had no 'a.d' field to fetch the ref exn by.");
        }

        banner("STEP 6 — query wallet key state, fetch the anchoring KEL event");
        KeyStateRecord confirmedState = confirmKeyState(client, walletAid);
        if (confirmedState != null) {
            System.out.println("Key-state query settled at sequence: " + confirmedState.getS()
                    + ", event SAID: " + confirmedState.getD());
        }

        List<KeyEventRecord> kel = client.keyEvents().get(walletAid);
        System.out.println("Wallet KEL has " + kel.size() + " event(s) visible to this agent.");

        KeyEvent anchoring = locateAnchoringEvent(kel, candidateEventSaid);
        boolean matches;
        if (anchoring == null) {
            System.out.println("No 'ixn' (interaction) event found on the wallet's KEL.");
            matches = false;
        } else {
            System.out.println("Anchoring event sequence (s): " + anchoring.getS());
            System.out.println("Anchoring event SAID (d):     " + anchoring.getD());
            System.out.println("Anchoring event seal list (a):");
            System.out.println(pretty(anchoring.getA()));
            matches = sealListContainsDigest(anchoring.getA(), digestQb64);
        }

        System.out.println();
        System.out.println("VERDICT SEAL_MATCHES_DIGEST: " + matches);

        try {
            client.notifications().mark(refNotification.getI());
        } catch (Exception e) {
            System.out.println("WARNING: failed to mark notification read: " + e.getMessage());
        }

        printVariantGuidance(KED_VARIANT, matches);
        if (!matches) {
            System.exit(1);
        }
    }

    // ------- Client / identifier setup ------- //

    private static SignifyClient connectClient() throws Exception {
        String bran = PASSCODE;
        if (bran == null || bran.isBlank()) {
            bran = Coring.randomPasscode();
            System.out.println("No PASSCODE provided; generated one for this session.");
            System.out.println("SAVE THIS to reuse the same local identifier on a later run:");
            System.out.println("  PASSCODE=" + bran);
        }

        SignifyClient client = new SignifyClient(KERI_URL, bran, Tier.LOW, KERI_BOOT_URL, null);
        try {
            client.connect();
        } catch (Exception e) {
            client.boot();
            client.connect();
        }
        return client;
    }

    /** Looks up {@code name}; creates a minimal witness-less identifier if it doesn't exist yet. */
    private static HabState ensureLocalIdentifier(SignifyClient client, String name) throws Exception {
        Optional<HabState> existing = client.identifiers().get(name);
        if (existing.isPresent()) {
            System.out.println("Reusing existing local identifier '" + name + "'.");
            return existing.get();
        }

        System.out.println("No local identifier named '" + name + "' found; creating a witness-less spike identity"
                + " (toad=0, no witnesses — fine for a throwaway spike AID, not for production use).");
        CreateIdentifierArgs kargs = CreateIdentifierArgs.builder()
                .toad(0)
                .wits(List.of())
                .build();
        System.out.println("Waiting for identifier creation to complete...");
        EventResult<?> created = client.identifiers().create(name, kargs);
        client.operations().wait(created.op(), boundedWait(30_000L));

        String agentEid = client.getAgent() != null ? client.getAgent().getPre() : null;
        if (agentEid != null) {
            try {
                System.out.println("Waiting for 'agent' end-role assignment to complete...");
                EventResult<?> endRole = client.identifiers().addEndRole(name, "agent", agentEid, null);
                client.operations().wait(endRole.op(), boundedWait(30_000L));
            } catch (Exception e) {
                System.out.println("WARNING: addEndRole('agent') failed (may already be set): " + e.getMessage());
            }
        }

        return client.identifiers().get(name)
                .orElseThrow(() -> new IllegalStateException("Identifier '" + name + "' still missing after creation."));
    }

    private static String resolveWalletAid(SignifyClient client, String oobiUrl) throws Exception {
        System.out.println("Resolving wallet OOBI: " + oobiUrl);
        OOBIOperation op = client.oobis().resolve(oobiUrl, WALLET_CONTACT_ALIAS);
        CompletedOOBIOperation completed =
                client.operations().wait(op, CompletedOOBIOperation.class, boundedWait(30_000L));
        return completed.getResponse().getI();
    }

    /**
     * A {@link Operations.WaitOptions} with an explicit timeout. Without this, {@code
     * Operations.wait(Operation)}'s default {@code WaitOptions} has a {@code null} abort-signal
     * timeout, which polls forever if an operation never leaves "pending" — this bounds every
     * {@code operations().wait(...)} call in this script so a stuck KERIA-side operation produces
     * a clear timeout error instead of a silent, indefinite hang.
     */
    private static Operations.WaitOptions boundedWait(long timeoutMs) {
        return Operations.WaitOptions.builder()
                .abortSignal(Operations.AbortSignal.builder().timeout(timeoutMs).build())
                .build();
    }

    // ------- KED variants ------- //

    private static Map<String, Object> buildKed(String variant, String walletAid, String digestQb64) throws Exception {
        LinkedHashMap<String, Object> ked = new LinkedHashMap<>();
        switch (variant) {
            case "A" -> ked.put("d", digestQb64);
            case "B" -> {
                ked.put("i", walletAid);
                ked.put("d", digestQb64);
            }
            case "C" -> {
                ked.put("i", walletAid);
                ked.put("d", "");
                ked.put("digest", digestQb64);
                return Saider.saidify(ked).sad();
            }
            default -> throw new IllegalArgumentException("Unknown KED_VARIANT '" + variant + "'.");
        }
        return ked;
    }

    // ------- Notification polling ------- //

    private static Notification waitForRefNotification(SignifyClient client) throws InterruptedException {
        Instant deadline = Instant.now().plus(NOTIFICATION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                Notifying.Notifications.NotificationListResponse response = client.notifications().list();
                for (Notification note : response.notes()) {
                    boolean unread = !Boolean.TRUE.equals(note.getR());
                    boolean routeMatches = note.getA() != null && REMOTESIGN_REF_ROUTES.contains(note.getA().getR());
                    if (unread && routeMatches) {
                        return note;
                    }
                }
            } catch (Exception e) {
                // A transient network/agent hiccup here must NOT abort the run: the human may have
                // already tapped "approve" in Veridian, and losing that means a full re-send. Log
                // and keep polling until the deadline instead (mirrors PublishExistingCredential's
                // waitForNotifications, which has the same try/catch around the same call).
                System.out.println();
                System.out.println("WARNING: notification list fetch failed, retrying: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            System.out.print(".");
            System.out.flush();
            Thread.sleep(NOTIFICATION_POLL_INTERVAL.toMillis());
        }
        System.out.println();
        return null;
    }

    // ------- Key state / KEL inspection ------- //

    /**
     * Best-effort confirmation that this agent's copy of the wallet's key state is caught up.
     * Per spec §4.6 item 5, this is used only to confirm KEL availability with bounded retries —
     * the actual anchoring event is read from {@code keyEvents().get(...)} below, not from here.
     */
    private static KeyStateRecord confirmKeyState(SignifyClient client, String walletAid) {
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                QueryOperation op = client.keyStates().query(walletAid, null);
                CompletedQueryOperation completed =
                        client.operations().wait(op, CompletedQueryOperation.class, boundedWait(15_000L));
                return completed.getResponse();
            } catch (Exception e) {
                System.out.println("Key-state query attempt " + attempt + "/" + maxAttempts
                        + " did not settle: " + e.getMessage());
            }
        }
        System.out.println("WARNING: could not confirm key state via /queries after " + maxAttempts
                + " attempts; proceeding to read KEL events directly anyway.");
        return null;
    }

    /**
     * Prefers the ixn event matching {@code candidateSaid} (learned from the ref exn payload).
     * Falls back to the LATEST ixn event if no candidate said was found or matched — this fallback
     * races with unrelated wallet events (the exact bug rev1 of the design had; see spec §4.6) so
     * it is flagged loudly rather than trusted silently.
     */
    private static KeyEvent locateAnchoringEvent(List<KeyEventRecord> kel, String candidateSaid) {
        List<KeyEvent> ixnEvents = kel.stream()
                .map(KeyEventRecord::getKed)
                .filter(ke -> "ixn".equals(ke.getT()))
                .toList();

        if (candidateSaid != null) {
            for (KeyEvent ke : ixnEvents) {
                if (candidateSaid.equals(ke.getD())) {
                    return ke;
                }
            }
            System.out.println("WARNING: no ixn event on the KEL matched candidate SAID '" + candidateSaid
                    + "'; falling back to the latest ixn event.");
        } else {
            System.out.println("WARNING: no candidate event SAID could be read from the ref exn; falling back to"
                    + " the latest ixn event. THIS RACES WITH UNRELATED WALLET EVENTS — treat the verdict below"
                    + " as provisional until the correlator field is confirmed from the printed ref exn JSON above.");
        }

        return ixnEvents.isEmpty() ? null : ixnEvents.get(ixnEvents.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static String extractCandidateEventSaid(Exn refExn) {
        if (refExn == null) {
            return null;
        }
        Object a = refExn.getA();
        if (!(a instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) a;
        for (String key : List.of("d", "anchor", "said", "eventSaid")) {
            Object v = map.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean sealListContainsDigest(Object sealListField, String digestQb64) {
        if (!(sealListField instanceof List)) {
            return false;
        }
        for (Object item : (List<Object>) sealListField) {
            if (item instanceof Map) {
                Object d = ((Map<String, Object>) item).get("d");
                if (digestQb64.equals(d)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------- Output helpers ------- //

    private static void banner(String title) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(title);
        System.out.println("=".repeat(78));
    }

    private static String pretty(Object o) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static void printVariantGuidance(String currentVariant, boolean matched) {
        System.out.println();
        System.out.println("-".repeat(78));
        if (matched) {
            System.out.println("KED_VARIANT=" + currentVariant + " produced SEAL_MATCHES_DIGEST: true.");
            System.out.println("Record this KED shape, the ref exn structure, and the thread-back field in"
                    + " keri_attestation/README.md under 'Spike findings'.");
        } else if (currentVariant.equals("A")) {
            System.out.println("KED_VARIANT=A did not produce a matching seal. Re-run with:");
            System.out.println("  KED_VARIANT=B WALLET_OOBI=\"" + WALLET_OOBI + "\" jbang docs/keri/spike/RemotesignAnchorSpike.java");
        } else if (currentVariant.equals("B")) {
            System.out.println("KED_VARIANT=B did not produce a matching seal. Re-run with:");
            System.out.println("  KED_VARIANT=C WALLET_OOBI=\"" + WALLET_OOBI + "\" jbang docs/keri/spike/RemotesignAnchorSpike.java");
        } else {
            System.out.println("KED_VARIANT=C did not produce a matching seal either.");
            System.out.println("All three variants (A, B, C) have been exercised without the wallet anchoring our");
            System.out.println("caller-chosen digest as the ixn seal. Per spec §4.4, this is a HARD BLOCKER:");
            System.out.println("Veridian's remotesign appears to anchor only a request-envelope SAID, which is NOT");
            System.out.println("interchangeable with the CIP-170 digest. STOP — escalate to the user/spec owner");
            System.out.println("before implementing Task 9.");
        }
        System.out.println("-".repeat(78));
    }
}
