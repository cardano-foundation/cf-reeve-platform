package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.signify.generated.keria.model.KeyEvent;
import org.cardanofoundation.signify.generated.keria.model.KeyEventRecord;

/**
 * Reads an AID's KEL and decides whether one of its interaction events anchors a given payload SAID.
 *
 * <p>Two callers need exactly this and reach it from opposite directions. {@link KeriAttestService}
 * runs a ceremony: it knows the sequence the KEL stood at before it sent the request, so it accepts
 * any event strictly after that floor. {@link AttestationImportVerifier} reads a finished attestation
 * off an imported card: it has no floor — the ceremony happened elsewhere, possibly months ago — but
 * the card names the event, so it demands that exact event. The seal predicate underneath is the same,
 * and keeping one copy is what stops the two paths from drifting into disagreeing about what a valid
 * anchor is.
 *
 * <p>What is anchored is always the <b>payload SAID</b> — the SAID of the saidified remotesign request
 * ({@link RemotesignRequestFactory}) — never the raw metadata digest that request carries. Passing the
 * digest here would fail against every genuine wallet.
 *
 * <p>Coded defensively over the pinned signify jar's raw {@code Object} shapes; field names were
 * confirmed against a live agent response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KelAnchorVerifier {

    /**
     * Projects typed key events into the generic maps the seal locators read.
     *
     * <p>This class used to walk raw {@code Object}s and test {@code instanceof Map}. Against the typed
     * return that test never matches: the KEL would come back EMPTY and every attestation would fail
     * with a seal mismatch, looking exactly like a wallet that never signed. Compile-clean, silently
     * wrong — hence the typed boundary here.
     */
    private static final ObjectMapper KEL_MAPPER = new ObjectMapper();

    private final KeriAttestationClient client;

    /** An anchoring-event locator: a SAID, a sequence, or neither. Both fields may be {@code null}. */
    public record AnchorCandidate(String said, String sequence) {

        public static final AnchorCandidate EMPTY = new AnchorCandidate(null, null);

        public boolean isEmpty() {
            return said == null && sequence == null;
        }
    }

    /** @return every {@code ixn} event in {@code aid}'s KEL, empty when the agent returns nothing usable. */
    public List<Map<String, Object>> fetchIxnEvents(String aid) throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        for (KeyEventRecord record : client.client().keyEvents().get(aid)) {
            KeyEvent ked = record.getKed();
            if (ked != null && "ixn".equals(ked.getT())) {
                events.add(KEL_MAPPER.convertValue(ked, new TypeReference<Map<String, Object>>() {
                }));
            }
        }
        return events;
    }

    /**
     * Import-side lookup: the event {@code candidate} names must exist AND seal {@code payloadSaid}.
     *
     * <p>No floor and no fallback scan. An imported card states which event anchors it; accepting a
     * different event that happens to carry the same seal would let a card point at one attestation and
     * be verified by another.
     *
     * <p>Every coordinate the candidate supplies must match the SAME event. Matching them as
     * alternatives — SAID first, else sequence — would accept a card naming a correct sequence beside a
     * SAID belonging to no event at all, and then persist those false coordinates as provenance.
     */
    public Optional<Map<String, Object>> findNamedAnchor(String aid, AnchorCandidate candidate, String payloadSaid)
            throws Exception {
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> event = locateCandidate(fetchIxnEvents(aid), candidate);
        return event != null && matchesEveryCoordinate(event, candidate)
                && sealContainsDigest(event.get("a"), payloadSaid)
                ? Optional.of(event) : Optional.empty();
    }

    private static boolean matchesEveryCoordinate(Map<String, Object> event, AnchorCandidate candidate) {
        if (candidate.said() != null && !candidate.said().equals(event.get("d"))) {
            return false;
        }
        return candidate.sequence() == null || candidate.sequence().equals(String.valueOf(event.get("s")));
    }

    /**
     * Locates the {@code ixn} event an explicit {@code candidate} names — by SAID first, then by
     * sequence.
     *
     * <p>No fallback to "the latest event" if neither matches: a caller holding an explicit candidate
     * that cannot be found must fail rather than shop for a different event.
     */
    public static Map<String, Object> locateCandidate(List<Map<String, Object>> ixnEvents,
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

    /**
     * Bounded scan over {@code ixnEvents} whose sequence falls within ({@code floorSequence},
     * {@code currentSequence}] — strictly after the floor, at or before the current key-state sequence
     * (hex-compared numerically) — returning the first whose seal contains {@code payloadSaid}, or
     * {@code null} when none in that window match.
     */
    public static Map<String, Object> scanForSealMatch(List<Map<String, Object>> ixnEvents, String floorSequence,
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

    /**
     * {@code event}'s sequence must be strictly after {@code floorSequence} (hex-compared numerically)
     * AND its seal must contain {@code payloadSaid}. Sequence equal to the floor is rejected: the floor
     * is the sequence observed before the request was sent, so the genuine anchoring event is always
     * strictly newer.
     */
    public static boolean satisfiesFloorAndDigest(Map<String, Object> event, String floorSequence,
            String payloadSaid) {
        long sn = parseHexSequence(String.valueOf(event.get("s")));
        if (sn <= parseHexSequence(floorSequence)) {
            return false;
        }
        return sealContainsDigest(event.get("a"), payloadSaid);
    }

    public static long parseHexSequence(String hexSequence) {
        return Long.parseLong(hexSequence, 16);
    }

    /** @return whether {@code sealField} is a seal list carrying {@code digestQb64} in a {@code d} field. */
    public static boolean sealContainsDigest(Object sealField, String digestQb64) {
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

}
