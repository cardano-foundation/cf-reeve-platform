package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.cesr.CESRStreamUtil;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchemaRegistry;
import org.cardanofoundation.signify.cesr.Saider;

/**
 * Decides whether a presented CESR credential chain is one this deployment accepts.
 *
 * <p>Three questions, in order, all of which must answer yes:
 * <ol>
 *   <li><b>Is this the credential we asked about?</b> The leaf is identified by its own SAID and must
 *       be issued to the expected holder — not merely "some credential in the stream issued to them".</li>
 *   <li><b>Is the chain structurally sound?</b> Every credential from the leaf upwards must be issued
 *       and not revoked, and each one's issuer must equal the issuee of the credential it cites via its
 *       {@code e} edge, up to a root.</li>
 *   <li><b>Do we trust who issued it?</b> Decided by the schema's own trust model — see
 *       {@link CredentialSchemaRegistry}.</li>
 * </ol>
 *
 * <p><b>Why a stream from an untrusted file can be believed at all.</b> Nothing here verifies KEL/TEL
 * signatures or witness receipts — that is KERIA's job for a stream KERIA admitted, and a card file
 * never passed through that path. Two properties stand in for it, and both are required:
 * <ul>
 *   <li><b>Every ACDC is self-addressing.</b> Its SAID is the digest of its own contents, so
 *       re-deriving it from the presented body and comparing proves the body was not edited. Change an
 *       issuee, a schema or an edge and the SAID no longer matches.</li>
 *   <li><b>Every SAID is confirmed against the issuing registry</b> ({@link CredentialTelStateReader}),
 *       which our agent reaches over the network rather than through the presenter — so a stream
 *       captured before a revocation no longer passes.</li>
 *   <li><b>Every credential was issued from a registry its own issuer controls.</b> The registry's id
 *       is the SAID of its inception event, and that event names its controller, so a credential cannot
 *       name one AID as issuer while being issued through somebody else's registry.</li>
 * </ul>
 * Together these bind presented content to a SAID, that SAID to a registry, and that registry to the
 * AID the credential claims issued it.
 *
 * <p><b>What is still not proven, and why it matters.</b> None of the above verifies a SIGNATURE. An
 * attacker who can author a registry inception naming a trusted AID as its controller, host that
 * registry somewhere our agent will resolve, and issue a SAID in it, still produces a chain that passes
 * every check here — because the one thing that would refute it is the trusted AID's own KEL, which
 * this class does not read. Closing that means either admitting the stream into KERIA and re-fetching
 * the credential through its verified path, or verifying KEL/TEL signatures and witness receipts
 * locally. Until then, this is content validation over an authenticated-at-the-edges stream, and the
 * strength of an imported card's badge rests on which registries the agent will resolve at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialChainValidator {

    private final CredentialSchemaRegistry registry;
    /**
     * Absent when no KERI agent is configured. Verification then cannot establish current revocation
     * state for anything, and every credential is rejected — the same fail-closed answer this class
     * gives when a configured agent cannot answer, rather than a quietly weaker check.
     */
    private final ObjectProvider<CredentialTelStateReader> telStateReaderProvider;

    /**
     * The accepted leaf credential and why it was trusted.
     *
     * @param leafIssuerAid  who issued the credential itself.
     * @param trustAnchorAid what trust was actually established against — the chain root for
     *                       {@code CHAINED}, the issuer itself for {@code STANDALONE}. These differ in a
     *                       vLEI chain (issuer is a QVI, anchor is GLEIF), and presenting one as the
     *                       other misstates both who issued the credential and why it was believed.
     * @param claims         the leaf's attribute block minus its structural {@code d}/{@code i} keys.
     */
    public record ValidatedCredential(String credentialSaid, String schemaSaid, String schemaName,
            String leafIssuerAid, String trustAnchorAid, TrustModel trustModel, Map<String, Object> claims,
            String policyFingerprint) {
    }

    /**
     * @param fullCesr              the full CESR stream, as returned by {@code client.credentials().get(said)}
     * @param expectedIssueeAid     the AID the leaf must be issued to (the wallet presenting it)
     * @param expectedCredentialSaid the leaf's own SAID. Required: without it, a stream could carry
     *                              several credentials issued to the same holder and the validator would
     *                              be free to pick whichever one happened to pass.
     * @param claimedSchemaSaid     the schema the caller was told this credential has, cross-checked
     *                              against the leaf's actual schema. {@code null} when the caller has no
     *                              prior claim (an IPEX admit learns the schema from the credential
     *                              itself) — the schema is still gated against the registry either way.
     */
    @SuppressWarnings("unchecked")
    public Either<ProblemDetail, ValidatedCredential> validate(String fullCesr, String expectedIssueeAid,
            String expectedCredentialSaid, String claimedSchemaSaid) {

        if (expectedIssueeAid == null || expectedIssueeAid.isBlank()) {
            return reject("No holder AID was supplied to validate the credential against.");
        }
        if (expectedCredentialSaid == null || expectedCredentialSaid.isBlank()) {
            return reject("No credential SAID was supplied, so the presented chain's leaf cannot be "
                    + "identified unambiguously.");
        }

        List<Map<String, Object>> parsed = CESRStreamUtil.parseCESRData(fullCesr);

        Map<String, Map<String, Object>> acdcBySaid = new LinkedHashMap<>();
        Map<String, Map<String, Object>> issByCredentialSaid = new HashMap<>();
        Map<String, String> registryControllerByRegistryId = new HashMap<>();
        Set<String> revokedCredentialSaids = new HashSet<>();

        for (Map<String, Object> eventData : parsed) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");
            Object t = event.get("t");
            if (t == null) {
                if (isAcdc(event)) {
                    String said = (String) event.get("d");
                    // Two ACDCs claiming one SAID: at most one can be genuine, and letting the later
                    // silently replace the earlier hands the choice of which to an attacker.
                    if (acdcBySaid.putIfAbsent(said, event) != null) {
                        return reject("Credential %s appears more than once in the chain with different "
                                + "contents.".formatted(said));
                    }
                }
                continue;
            }
            switch (t.toString()) {
                case "iss" -> issByCredentialSaid.put((String) event.get("i"), event);
                // Registry inception: names the AID that controls the registry ("ii"). Needed to check
                // that a credential was issued from a registry its own issuer controls.
                case "vcp" -> {
                    // A registry's id IS the SAID of its inception event, so a vcp whose own d does not
                    // equal the registry id it claims is simply somebody else's registry id with an
                    // attacker's controller written under it.
                    String registryId = (String) event.get("i");
                    if (registryId != null && registryId.equals(event.get("d"))) {
                        registryControllerByRegistryId.put(registryId, (String) event.get("ii"));
                    }
                }
                // KERI TELs are append-only and revocation is terminal — a rev event can never be
                // "undone" by a later iss for the same credential SAID (SAIDs are fixed at issuance),
                // so presence anywhere in the stream is sufficient; stream order need not be checked.
                case "rev" -> revokedCredentialSaids.add((String) event.get("i"));
                default -> {
                    // icp/ixn/rot/vcp/... are irrelevant to content validation.
                }
            }
        }

        Map<String, Object> leaf = acdcBySaid.get(expectedCredentialSaid);
        if (leaf == null) {
            return reject("Credential %s is not present in the chain.".formatted(expectedCredentialSaid));
        }
        String leafIssuee = issuee(leaf);
        if (!expectedIssueeAid.equals(leafIssuee)) {
            return reject("Credential %s is issued to %s, not to %s."
                    .formatted(expectedCredentialSaid, leafIssuee, expectedIssueeAid));
        }

        String leafSchema = (String) leaf.get("s");
        if (claimedSchemaSaid != null && !claimedSchemaSaid.equals(leafSchema)) {
            return reject("Credential %s has schema %s, not the claimed %s."
                    .formatted(expectedCredentialSaid, leafSchema, claimedSchemaSaid));
        }
        CredentialSchema schema = registry.find(leafSchema).orElse(null);
        if (schema == null) {
            return reject("Credential %s has schema %s, which this deployment does not accept."
                    .formatted(expectedCredentialSaid, leafSchema));
        }

        String leafIssuer = (String) leaf.get("i");
        if (leafIssuer == null) {
            return reject("Credential %s has no issuer (i) — malformed chain.".formatted(expectedCredentialSaid));
        }

        Set<String> rootIssuers = new LinkedHashSet<>();
        Either<ProblemDetail, Void> ancestry = validateAncestry(leaf, new HashSet<>(), acdcBySaid,
                issByCredentialSaid, registryControllerByRegistryId, revokedCredentialSaids, rootIssuers);
        if (ancestry.isLeft()) {
            return Either.left(ancestry.getLeft());
        }

        Either<ProblemDetail, String> anchor = checkTrust(schema, leafIssuer, rootIssuers);
        if (anchor.isLeft()) {
            return Either.left(anchor.getLeft());
        }

        return Either.right(new ValidatedCredential(expectedCredentialSaid, leafSchema, schema.displayName(),
                leafIssuer, anchor.get(), schema.trustModel(), claims(leaf), schema.policyFingerprint()));
    }

    /**
     * Applies the schema's trust model.
     *
     * <p>For {@code CHAINED}, <b>every</b> root the chain terminates at must be trusted, not merely one
     * of them: a chain with a second branch ending somewhere else is exactly the shape an attacker would
     * graft on, and "at least one root is fine" would wave it through.
     *
     * @return the AID trust was established against.
     */
    private static Either<ProblemDetail, String> checkTrust(CredentialSchema schema, String leafIssuer,
            Set<String> rootIssuers) {
        List<String> anchors = schema.trustAnchors();
        if (schema.trustModel() == TrustModel.STANDALONE) {
            if (!anchors.contains(leafIssuer)) {
                return reject("Credential issuer %s is not a trusted issuer of schema %s."
                        .formatted(leafIssuer, schema.displayName()));
            }
            return Either.right(leafIssuer);
        }
        if (rootIssuers.isEmpty()) {
            return reject("Credential chain for schema %s terminates at no root, so it cannot be traced "
                    + "to a trusted root.".formatted(schema.displayName()));
        }
        for (String root : rootIssuers) {
            if (!anchors.contains(root)) {
                return reject("Credential chain for schema %s terminates at root issuer %s, which is not "
                        + "a trusted root.".formatted(schema.displayName(), root));
            }
        }
        return Either.right(rootIssuers.iterator().next());
    }

    // --- ancestry walk: TEL state at every link, edges chained to a root, cycle-safe ---

    /**
     * Walks {@code node} up to a root, checking TEL state at every link and that each link's issuer
     * equals the issuee of the credential it cites via its {@code e} edge(s), collecting the issuer of
     * every terminal root into {@code rootIssuers}.
     *
     * <p>Cycle safety: {@code visited} is a single set shared across the <em>whole</em> walk (not
     * copied per branch), so it always terminates — a malicious/malformed stream citing a credential
     * already on the current walk is rejected outright rather than looped over. The one accepted
     * trade-off is that a legitimate multi-edge DAG which converges on a shared ancestor from two
     * different branches would be (conservatively, harmlessly) rejected as a "cycle" too; this doesn't
     * arise for the linear, one-edge-per-level chains this ecosystem actually issues (root → QVI → LE →
     * ...), so it is not specifically handled.
     */
    @SuppressWarnings("unchecked")
    private Either<ProblemDetail, Void> validateAncestry(Map<String, Object> node, Set<String> visited,
            Map<String, Map<String, Object>> acdcBySaid, Map<String, Map<String, Object>> issByCredentialSaid,
            Map<String, String> registryControllerByRegistryId, Set<String> revokedCredentialSaids,
            Set<String> rootIssuers) {

        String nodeSaid = (String) node.get("d");
        if (!visited.add(nodeSaid)) {
            return reject("Credential chain contains a cycle at %s.".formatted(nodeSaid));
        }

        // Authenticity before anything is read off this credential: an unedited body (its SAID still
        // derives) whose SAID the issuer's registry currently vouches for. Everything below — issuer,
        // issuee, edges — is only meaningful once both hold.
        Either<ProblemDetail, Void> authentic = checkSelfAddressing(nodeSaid, node);
        if (authentic.isLeft()) {
            return authentic;
        }
        Either<ProblemDetail, Void> tel = checkTel(nodeSaid, (String) node.get("ri"), issByCredentialSaid,
                revokedCredentialSaids);
        if (tel.isLeft()) {
            return tel;
        }

        // A structurally-plausible ACDC (isAcdc() only requires the "i" key present, not its value
        // non-null) can still carry a null issuer. Guarded here, once, before either branch below
        // reads it via .equals() — a malformed/hostile chain is rejected outright instead of NPE-ing
        // the caller (see KeriCredentialService.presentCredential's javadoc for the matching
        // defense-in-depth wrapper around this whole call).
        String nodeIssuer = (String) node.get("i");
        if (nodeIssuer == null) {
            return reject("Credential %s has no issuer (i) — malformed chain.".formatted(nodeSaid));
        }

        Either<ProblemDetail, Void> registryOwned = checkRegistryBelongsToIssuer(nodeSaid, nodeIssuer,
                (String) node.get("ri"), registryControllerByRegistryId);
        if (registryOwned.isLeft()) {
            return registryOwned;
        }

        List<Map.Entry<String, Object>> edges = substantiveEdges(node);
        if (edges.isEmpty()) {
            rootIssuers.add(nodeIssuer);
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
            // The edge states which schema the parent is expected to be. Not checking it lets an
            // intermediary cite an unrelated credential from a trusted issuer as authority for a
            // schema that credential says nothing about.
            Object edgeSchema = edge.get("s");
            if (edgeSchema != null && !edgeSchema.equals(parent.get("s"))) {
                return reject(("Credential %s edge '%s' expects parent schema %s but credential %s has "
                        + "schema %s.").formatted(nodeSaid, edgeEntry.getKey(), edgeSchema, parentSaid,
                        parent.get("s")));
            }
            Either<ProblemDetail, Void> parentResult = validateAncestry(parent, visited, acdcBySaid,
                    issByCredentialSaid, registryControllerByRegistryId, revokedCredentialSaids, rootIssuers);
            if (parentResult.isLeft()) {
                return parentResult;
            }
        }
        return Either.right(null);
    }

    /**
     * Two questions, both of which must pass.
     *
     * <p>The presented stream must contain an issuance and no revocation — cheap, and it catches a
     * malformed chain before anything goes over the network. Then the issuer's own registry is asked
     * whether the credential is issued <em>now</em>, which is the only one of the two an attacker
     * cannot answer for us: a stream captured before a revocation passes the first check and fails
     * this one.
     */
    private Either<ProblemDetail, Void> checkTel(String credentialSaid, String registryId,
            Map<String, Map<String, Object>> issByCredentialSaid, Set<String> revokedCredentialSaids) {
        if (!issByCredentialSaid.containsKey(credentialSaid)) {
            return reject("Credential %s has no issuance (iss) event in the presented chain.".formatted(credentialSaid));
        }
        if (revokedCredentialSaids.contains(credentialSaid)) {
            return reject("Credential %s has been revoked.".formatted(credentialSaid));
        }
        CredentialTelStateReader telStateReader = telStateReaderProvider.getIfAvailable();
        if (telStateReader == null) {
            return reject(("Credential %s cannot be checked for revocation: no KERI agent is configured to "
                    + "ask its registry.").formatted(credentialSaid));
        }
        return switch (telStateReader.statusOf(registryId, credentialSaid)) {
            case ISSUED -> Either.right(null);
            case REVOKED -> reject("Credential %s has been revoked by its issuer.".formatted(credentialSaid));
            case UNKNOWN -> reject(("Credential %s could not be confirmed as currently issued by its registry "
                    + "(%s). Its current state is unknown, which is not the same as valid.")
                    .formatted(credentialSaid, registryId));
        };
    }

    /**
     * Re-derives an ACDC's SAID from the body as presented and requires it to match the SAID claimed.
     *
     * <p>This is what makes card-carried content trustworthy without verifying signatures: the SAID is
     * a digest of the contents, so a body edited after issuance no longer derives to the SAID the
     * issuer's registry vouches for. Without it, an attacker could take a genuinely-issued SAID and
     * present entirely different attributes under it.
     */
    /**
     * Requires a credential to have been issued from a registry its own issuer controls.
     *
     * <p>Without this, a credential can name any AID as its issuer while being issued through a
     * registry somebody else controls — and the registry lookup, which only asks "is this SAID issued
     * in this registry", would happily confirm it. Self-addressing binds the body to the SAID; this
     * binds the SAID to the issuer it claims.
     *
     * <p>The registry inception event ({@code vcp}) names its controller in {@code ii}. It has to be
     * present in the stream: a chain that omits the registry it was issued from is one whose issuance
     * cannot be attributed to anybody.
     */
    private static Either<ProblemDetail, Void> checkRegistryBelongsToIssuer(String credentialSaid,
            String issuer, String registryId, Map<String, String> registryControllerByRegistryId) {
        if (registryId == null || registryId.isBlank()) {
            return reject("Credential %s names no registry (ri), so its issuance cannot be attributed."
                    .formatted(credentialSaid));
        }
        String controller = registryControllerByRegistryId.get(registryId);
        if (controller == null) {
            return reject(("Credential %s was issued from registry %s, whose inception (vcp) event is not "
                    + "in the presented chain — the registry cannot be tied to an issuer.")
                    .formatted(credentialSaid, registryId));
        }
        if (!issuer.equals(controller)) {
            return reject(("Credential %s claims issuer %s but was issued from registry %s, which is "
                    + "controlled by %s.").formatted(credentialSaid, issuer, registryId, controller));
        }
        return Either.right(null);
    }

    private static Either<ProblemDetail, Void> checkSelfAddressing(String credentialSaid,
            Map<String, Object> acdc) {
        boolean derived;
        try {
            derived = new Saider(credentialSaid).verify(acdc, false, true);
        } catch (RuntimeException e) {
            return reject("Credential %s does not carry a well-formed SAID: %s"
                    .formatted(credentialSaid, e.getMessage()));
        }
        if (!derived) {
            return reject(("Credential %s does not hash to its own SAID — its contents were altered after "
                    + "issuance.").formatted(credentialSaid));
        }
        return Either.right(null);
    }

    // --- ACDC helpers ---

    /** ACDCs carry no {@code "t"} (event-type) field — identified by having {@code s}/{@code a}/{@code i}
     *  instead, same idiom {@link CesrChainReducer} uses. */
    private static boolean isAcdc(Map<String, Object> event) {
        return event.containsKey("s") && event.containsKey("a") && event.containsKey("i") && event.get("s") != null;
    }

    @SuppressWarnings("unchecked")
    private static String issuee(Map<String, Object> acdc) {
        Object a = acdc.get("a");
        return a instanceof Map<?, ?> am ? (String) am.get("i") : null;
    }

    /** The leaf's attributes, minus the structural {@code d} (the block's own SAID) and {@code i} (the
     *  issuee, reported separately) — what is left is what the credential actually asserts. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> claims(Map<String, Object> acdc) {
        Object a = acdc.get("a");
        if (!(a instanceof Map<?, ?> am)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) am).entrySet()) {
            if (!"d".equals(entry.getKey()) && !"i".equals(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(result);
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
