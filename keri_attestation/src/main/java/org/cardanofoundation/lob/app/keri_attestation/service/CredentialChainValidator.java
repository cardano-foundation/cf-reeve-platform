package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;

/**
 * Validates a presented full CESR credential chain's <em>contents</em> before it is accepted (design
 * §4.3): the leaf credential must be issued to the expected holder AID under an allowlisted schema,
 * and every credential from the leaf up to a root must be currently-issued (not revoked) and chain
 * cryptographic authority correctly — each credential's issuer must equal the AID that the credential
 * it cites via its {@code e} edge was itself issued <em>to</em>, all the way up to a credential issued
 * by a trusted root AID.
 *
 * <p><b>Boundary with KERIA:</b> this class only inspects the parsed event/attachment <em>content</em>
 * of the presented stream — it does not verify KEL/TEL cryptographic signatures, key-state, or witness
 * receipts. That deep verification is KERIA's job when the stream was originally admitted into this
 * agent's store (the events would not have been accepted into {@code client.credentials().get(...)}
 * were they not already signature-valid); this validator instead enforces the platform's own trust
 * policy (issuee identity, schema allowlist, trusted-root allowlist, revocation) against content KERIA
 * has already vouched for structurally.
 */
@Service
public class CredentialChainValidator {

    /** The validated leaf credential — {@code credentialSaid} is its own {@code d}, {@code schemaSaid}
     *  its {@code s}. */
    public record ValidatedCredential(String credentialSaid, String schemaSaid) {
    }

    /**
     * @param fullCesr           the full CESR stream, as returned by {@code client.credentials().get(said)}
     * @param expectedIssueeAid  the AID the leaf credential must be issued to (the linked wallet AID
     *                           presenting it)
     * @param allowedSchemaSaids schema SAIDs the leaf credential's own schema must be a member of
     *                           ({@code credential-policy.schema-saids})
     * @param trustedRootAids    issuer AIDs trusted as chain roots ({@code credential-policy.trusted-root-aids})
     */
    @SuppressWarnings("unchecked")
    public Either<ProblemDetail, ValidatedCredential> validate(String fullCesr, String expectedIssueeAid,
            List<String> allowedSchemaSaids, List<String> trustedRootAids) {

        List<Map<String, Object>> parsed = CESRStreamUtil.parseCESRData(fullCesr);

        Map<String, Map<String, Object>> acdcBySaid = new LinkedHashMap<>();
        Map<String, Map<String, Object>> issByCredentialSaid = new HashMap<>();
        Set<String> revokedCredentialSaids = new HashSet<>();

        for (Map<String, Object> eventData : parsed) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");
            Object t = event.get("t");
            if (t == null) {
                if (isAcdc(event)) {
                    acdcBySaid.put((String) event.get("d"), event);
                }
                continue;
            }
            switch (t.toString()) {
                case "iss" -> issByCredentialSaid.put((String) event.get("i"), event);
                // KERI TELs are append-only and revocation is terminal — a rev event can never be
                // "undone" by a later iss for the same credential SAID (SAIDs are fixed at issuance),
                // so presence anywhere in the stream is sufficient; stream order need not be checked.
                case "rev" -> revokedCredentialSaids.add((String) event.get("i"));
                default -> {
                    // icp/ixn/rot/vcp/... are irrelevant to content validation.
                }
            }
        }

        Map<String, Object> leaf = findByIssuee(acdcBySaid, expectedIssueeAid);
        if (leaf == null) {
            return reject("No credential in the presented chain is issued to %s.".formatted(expectedIssueeAid));
        }
        String leafSaid = (String) leaf.get("d");
        String leafSchema = (String) leaf.get("s");
        if (allowedSchemaSaids == null || !allowedSchemaSaids.contains(leafSchema)) {
            return reject("Leaf credential %s schema %s is not in the allowed schema list."
                    .formatted(leafSaid, leafSchema));
        }

        Either<ProblemDetail, Void> ancestry = validateAncestry(leaf, new HashSet<>(), acdcBySaid,
                issByCredentialSaid, revokedCredentialSaids, trustedRootAids);
        if (ancestry.isLeft()) {
            return Either.left(ancestry.getLeft());
        }

        return Either.right(new ValidatedCredential(leafSaid, leafSchema));
    }

    // --- ancestry walk: TEL state at every link, edges chained to a trusted root, cycle-safe ---

    /**
     * Walks {@code node} up to a root, checking TEL state at every link and that each link's issuer
     * equals the issuee of the credential it cites via its {@code e} edge(s).
     *
     * <p>Cycle safety: {@code visited} is a single set shared across the <em>whole</em> walk (not
     * copied per branch), so it always terminates — a malicious/malformed stream citing a credential
     * already on the current walk is rejected outright rather than looped over. The one accepted
     * trade-off is that a legitimate multi-edge DAG which converges on a shared ancestor from two
     * different branches would be (conservatively, harmlessly) rejected as a "cycle" too; this doesn't
     * arise for the linear, one-edge-per-level chains this ecosystem actually issues (root → QVI → LE →
     * ...), so it is not specifically handled.
     */
    private Either<ProblemDetail, Void> validateAncestry(Map<String, Object> node, Set<String> visited,
            Map<String, Map<String, Object>> acdcBySaid, Map<String, Map<String, Object>> issByCredentialSaid,
            Set<String> revokedCredentialSaids, List<String> trustedRootAids) {

        String nodeSaid = (String) node.get("d");
        if (!visited.add(nodeSaid)) {
            return reject("Credential chain contains a cycle at %s.".formatted(nodeSaid));
        }

        Either<ProblemDetail, Void> tel = checkTel(nodeSaid, issByCredentialSaid, revokedCredentialSaids);
        if (tel.isLeft()) {
            return tel;
        }

        // A structurally-plausible ACDC (isAcdc() only requires the "i" key present, not its value
        // non-null) can still carry a null issuer. Guarded here, once, before either branch below
        // reads it via .equals() — a malformed/hostile chain is rejected outright instead of NPE-ing
        // the caller (see KeriCredentialService.awaitPresentation's javadoc for the matching
        // defense-in-depth wrapper around this whole call).
        String nodeIssuer = (String) node.get("i");
        if (nodeIssuer == null) {
            return reject("Credential %s has no issuer (i) — malformed chain.".formatted(nodeSaid));
        }

        List<Map.Entry<String, Object>> edges = substantiveEdges(node);
        if (edges.isEmpty()) {
            if (trustedRootAids == null || !trustedRootAids.contains(nodeIssuer)) {
                return reject("Credential chain issuer %s is not a trusted root AID.".formatted(nodeIssuer));
            }
            return Either.right(null);
        }

        for (Map.Entry<String, Object> edgeEntry : edges) {
            Object edgeValue = edgeEntry.getValue();
            Map<String, Object> edge = edgeValue instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            String parentSaid = edge != null ? (String) edge.get("n") : null;
            Map<String, Object> parent = parentSaid != null ? acdcBySaid.get(parentSaid) : null;
            if (parent == null) {
                return reject("Credential %s edge '%s' references unknown parent credential %s."
                        .formatted(nodeSaid, edgeEntry.getKey(), parentSaid));
            }
            String parentIssuee = issuee(parent);
            if (!nodeIssuer.equals(parentIssuee)) {
                return reject("Credential %s issuer %s does not match parent credential %s's issuee %s."
                        .formatted(nodeSaid, nodeIssuer, parentSaid, parentIssuee));
            }
            Either<ProblemDetail, Void> parentResult = validateAncestry(parent, visited, acdcBySaid,
                    issByCredentialSaid, revokedCredentialSaids, trustedRootAids);
            if (parentResult.isLeft()) {
                return parentResult;
            }
        }
        return Either.right(null);
    }

    private static Either<ProblemDetail, Void> checkTel(String credentialSaid,
            Map<String, Map<String, Object>> issByCredentialSaid, Set<String> revokedCredentialSaids) {
        if (!issByCredentialSaid.containsKey(credentialSaid)) {
            return reject("Credential %s has no issuance (iss) event in the presented chain.".formatted(credentialSaid));
        }
        if (revokedCredentialSaids.contains(credentialSaid)) {
            return reject("Credential %s has been revoked.".formatted(credentialSaid));
        }
        return Either.right(null);
    }

    // --- ACDC helpers ---

    /** ACDCs carry no {@code "t"} (event-type) field — identified by having {@code s}/{@code a}/{@code i}
     *  instead, same idiom as {@code docs/keri/advanced/PublishExistingCredential.java#strip}. */
    private static boolean isAcdc(Map<String, Object> event) {
        return event.containsKey("s") && event.containsKey("a") && event.containsKey("i") && event.get("s") != null;
    }

    private static Map<String, Object> findByIssuee(Map<String, Map<String, Object>> acdcBySaid,
            String expectedIssueeAid) {
        for (Map<String, Object> acdc : acdcBySaid.values()) {
            if (expectedIssueeAid.equals(issuee(acdc))) {
                return acdc;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String issuee(Map<String, Object> acdc) {
        Object a = acdc.get("a");
        return a instanceof Map<?, ?> am ? (String) am.get("i") : null;
    }

    /** The {@code e} (edges) map's own {@code d} entry is the edge block's own SAID, not an edge —
     *  every other entry is {@code {n: <parent credential SAID>, s: <parent schema SAID>, ...}}. */
    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, Object>> substantiveEdges(Map<String, Object> acdc) {
        Object e = acdc.get("e");
        if (!(e instanceof Map<?, ?> em) || em.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Object>> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : em.entrySet()) {
            if (!"d".equals(entry.getKey())) {
                result.add(Map.entry((String) entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private static <T> Either<ProblemDetail, T> reject(String detail) {
        return Either.left(KeriAttestationProblems.unprocessable(KeriAttestationProblems.CREDENTIAL_REJECTED, detail));
    }
}
