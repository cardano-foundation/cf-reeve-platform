package org.cardanofoundation.lob.app.keri_attestation.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The set of credential schemas this deployment accepts, keyed by schema SAID.
 *
 * <p>This is the single place that answers "do we accept this schema, and who is allowed to issue it?"
 * Verification consults it; nothing else decides trust. Adding a schema means adding a configuration
 * entry — see {@link CredentialSchema}.
 *
 * <p><b>Validated at construction, and a failure is fatal.</b> A registry that is present but wrong is
 * more dangerous than one that is absent: an empty trust-anchor list reads naturally as "no
 * restriction", and a mis-templated environment variable produces exactly that. Rejecting at startup
 * turns a silently-permissive deployment into one that does not boot, which is the failure an operator
 * can actually see. All problems are collected and reported together, so a misconfigured deployment is
 * fixed in one pass rather than one restart per mistake.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation", name = "enabled", havingValue = "true")
public class CredentialSchemaRegistry {

    private final Map<String, CredentialSchema> bySaid;

    /** The container's entry point. Explicit, because the list constructor below is public too. */
    @Autowired
    public CredentialSchemaRegistry(KeriAttestationProperties properties) {
        this(effectiveSchemas(properties));
    }

    /**
     * Resolves what this deployment actually configured, bridging one release of the retired
     * {@code credential-policy.schema-saids} / {@code trusted-root-aids} pair (§10.5).
     *
     * <p>A synthesised entry is {@code CHAINED}, because that pair only ever expressed the vLEI shape.
     * It is then validated like any other, so a deployment that set schema SAIDs but no roots fails to
     * start — correct, since it has no trust policy at all. The bridge exists so such a deployment
     * keeps feeding its schema list to the IPEX apply while it migrates, not to keep accepting
     * anything.
     */
    private static List<CredentialSchema> effectiveSchemas(KeriAttestationProperties properties) {
        List<CredentialSchema> configured = properties.credentialSchemas();
        KeriAttestationProperties.CredentialPolicy legacy = properties.credentialPolicy();
        List<String> legacySaids = legacy == null || legacy.schemaSaids() == null
                ? List.of() : legacy.schemaSaids();

        // ABSENT and EMPTY mean different things. An explicitly empty credential-schemas is a
        // deployment saying "accept nothing"; falling back to stale legacy anchors would override that
        // with the opposite. Only an absent list reaches the bridge.
        if (configured != null) {
            if (!legacySaids.isEmpty()) {
                log.warn("Both lob.keri-attestation.credential-schemas and the deprecated "
                        + "credential-policy.schema-saids are set. credential-schemas wins; the legacy "
                        + "keys are ignored and should be removed.");
            }
            return configured;
        }
        if (legacySaids.isEmpty()) {
            return List.of();
        }
        List<String> legacyRoots = legacy.trustedRootAids() == null ? List.of() : legacy.trustedRootAids();
        log.warn("lob.keri-attestation.credential-schemas is not set; falling back to the deprecated "
                + "credential-policy.schema-saids/trusted-root-aids for {} schema(s), treated as CHAINED. "
                + "This fallback is removed next release — migrate to credential-schemas.",
                legacySaids.size());
        return legacySaids.stream()
                .map(said -> new CredentialSchema(said, null, CredentialSchema.TrustModel.CHAINED,
                        legacyRoots, List.of(), List.of()))
                .toList();
    }

    public CredentialSchemaRegistry(List<CredentialSchema> schemas) {
        List<CredentialSchema> configured = schemas == null ? List.of() : schemas;
        List<String> problems = validate(configured);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "lob.keri-attestation.credential-schemas is invalid, refusing to start:\n  - "
                            + String.join("\n  - ", problems));
        }
        Map<String, CredentialSchema> index = new LinkedHashMap<>();
        for (CredentialSchema schema : configured) {
            index.put(schema.said(), schema);
        }
        // Insertion order is preserved deliberately: configuration order is what an operator reads back
        // in logs and what allOobis() resolves in. Map.copyOf would scramble it.
        this.bySaid = Collections.unmodifiableMap(index);
        if (bySaid.isEmpty()) {
            log.warn("No credential schemas are configured (lob.keri-attestation.credential-schemas), so NO "
                    + "credential will be accepted. Every credential presentation and attested card import "
                    + "will be rejected until at least one schema is configured.");
        } else {
            log.info("Credential schema registry loaded with {} schema(s): {}", bySaid.size(),
                    bySaid.values().stream().map(CredentialSchema::displayName).toList());
        }
    }

    /** @return the schema entry for {@code said}, or empty when this deployment does not accept it. */
    public Optional<CredentialSchema> find(String said) {
        return said == null ? Optional.empty() : Optional.ofNullable(bySaid.get(said));
    }

    /** @return whether {@code said} is a schema this deployment accepts at all (the §6 schema gate). */
    public boolean accepts(String said) {
        return find(said).isPresent();
    }

    /** @return every configured schema SAID, in configuration order. */
    public List<String> schemaSaids() {
        return List.copyOf(bySaid.keySet());
    }

    /** @return every configured OOBI across all schemas, de-duplicated, in configuration order. */
    public List<String> allOobis() {
        return bySaid.values().stream()
                .flatMap(s -> s.oobis() == null ? java.util.stream.Stream.<String>empty() : s.oobis().stream())
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return bySaid.isEmpty();
    }

    /**
     * @return every problem found, empty when the configuration is usable. A schema with no trust
     *         anchors is a problem and not a permissive default: see this class's own javadoc.
     */
    private static List<String> validate(List<CredentialSchema> schemas) {
        List<String> problems = new ArrayList<>();
        // An EMPTY registry is not a problem: it accepts nothing, which is the safe direction, and is
        // the honest state of a deployment that has not configured credential verification. What is a
        // problem is a schema that is present but names no trust anchors — that one reads as "accept
        // anyone" and is what this refuses to boot on.
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < schemas.size(); i++) {
            CredentialSchema schema = schemas.get(i);
            String where = "credential-schemas[%d]".formatted(i);
            if (schema == null) {
                problems.add(where + " is empty");
                continue;
            }
            if (isBlank(schema.said())) {
                problems.add(where + " has no said");
            } else if (seen.contains(schema.said())) {
                problems.add("%s duplicates the schema said %s".formatted(where, schema.said()));
            } else {
                seen.add(schema.said());
            }
            if (schema.trustModel() == null) {
                problems.add("%s (%s) has no trust-model; expected CHAINED or STANDALONE"
                        .formatted(where, schema.said()));
                continue;
            }
            validateAnchors(problems, where, schema);
        }
        return problems;
    }

    private static void validateAnchors(List<String> problems, String where, CredentialSchema schema) {
        String anchorProperty = schema.trustModel() == CredentialSchema.TrustModel.CHAINED
                ? "trusted-roots" : "trusted-issuers";
        List<String> anchors = schema.trustAnchors();
        if (anchors.isEmpty()) {
            problems.add("%s (%s) is %s but names no %s; an empty trust list would accept any issuer"
                    .formatted(where, schema.said(), schema.trustModel(), anchorProperty));
            return;
        }
        for (int i = 0; i < anchors.size(); i++) {
            if (isBlank(anchors.get(i))) {
                // A blank entry is almost always an unresolved ${ENV_VAR:} placeholder. Dropping it
                // quietly is how a trust list becomes empty without anyone noticing.
                problems.add("%s (%s) has a blank entry in %s[%d]; an unset environment variable?"
                        .formatted(where, schema.said(), anchorProperty, i));
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
