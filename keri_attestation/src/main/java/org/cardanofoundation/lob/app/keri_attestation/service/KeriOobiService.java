package org.cardanofoundation.lob.app.keri_attestation.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriAttestationCeremonyRepository;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.coring.Operations;

/**
 * Resolves a user's wallet OOBI into an AID and creates/updates their {@link KeriIdentityLinkEntity}
 * (design §4.3/§4.7).
 *
 * <p>All syntactic validation ({@link #validate}) runs before any {@link SignifyClient} call — an
 * invalid OOBI URL never touches the KERI agent. Once validated, the URL is resolved against the
 * agent (alias = the caller's {@code userId}, stable per user) and the resulting AID is verified via
 * {@code contacts().get(aid)} before anything is persisted. Persistence then branches on the user's
 * existing {@link KeriIdentityLinkEntity} (design §4.7): no link creates one at
 * {@code bindingVersion=1}; the same AID just refreshes {@code oobiUrl}; a different AID requires
 * {@code relink=true} and, when granted, bumps {@code bindingVersion}, clears every field that only
 * makes sense under the old identity, and fails every one of the user's still-open ceremonies so a
 * ceremony created under the stale binding can never be consumed (mirrors
 * {@link CeremonyService#validateAndConsume}'s own binding-version check, just proactive instead of
 * reactive).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriOobiService {

    static final int MAX_OOBI_URL_LENGTH = 2048;
    private static final long RESOLVE_TIMEOUT_MILLIS = 15_000L;
    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    private static final Set<CeremonyState> TERMINAL_STATES =
            EnumSet.of(CeremonyState.CONSUMED, CeremonyState.FAILED, CeremonyState.EXPIRED);

    private final KeriAttestationClient client;
    private final KeriIdentityLinkRepository identityLinkRepository;
    private final KeriAttestationCeremonyRepository ceremonyRepository;

    public Either<ProblemDetail, String> resolveUserOobi(String userId, String oobiUrl, boolean relink) {
        Either<ProblemDetail, String> validated = validate(oobiUrl);
        if (validated.isLeft()) {
            return validated;
        }
        String aid = validated.get();

        Either<ProblemDetail, Void> resolved = resolveAndVerify(userId, oobiUrl, aid);
        if (resolved.isLeft()) {
            return Either.left(resolved.getLeft());
        }

        return persistLink(userId, aid, oobiUrl, relink);
    }

    // --- validation (design §4.3): runs before any client call, so an invalid URL never touches
    //     the KERI agent. ---

    private static Either<ProblemDetail, String> validate(String oobiUrl) {
        if (oobiUrl == null || oobiUrl.isBlank()) {
            return Either.left(invalid("OOBI URL must not be blank."));
        }
        if (oobiUrl.length() > MAX_OOBI_URL_LENGTH) {
            return Either.left(invalid(
                    "OOBI URL exceeds the maximum length of %d characters.".formatted(MAX_OOBI_URL_LENGTH)));
        }
        URI uri;
        try {
            uri = new URI(oobiUrl);
        } catch (URISyntaxException e) {
            return Either.left(invalid("OOBI URL is not a syntactically valid URL: %s".formatted(e.getMessage())));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Either.left(invalid("OOBI URL must use the https scheme."));
        }
        Matcher matcher = OOBI_AID_PATTERN.matcher(oobiUrl);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            return Either.left(invalid("OOBI URL must contain a non-empty /oobi/{aid} path segment."));
        }
        return Either.right(matcher.group(1));
    }

    // --- resolve against the KERI agent and verify the AID landed in contacts ---

    private Either<ProblemDetail, Void> resolveAndVerify(String userId, String oobiUrl, String aid) {
        try {
            Object resolveResult = client.client().oobis().resolve(oobiUrl, userId);
            Operations.WaitOptions waitOptions = Operations.WaitOptions.builder()
                    .abortSignal(Operations.AbortSignal.builder().timeout(RESOLVE_TIMEOUT_MILLIS).build())
                    .build();
            client.client().operations().wait(Operation.fromObject(resolveResult), waitOptions);

            Optional<Object> contact = client.client().contacts().get(aid);
            if (contact.isEmpty()) {
                return Either.left(invalid(
                        "AID %s could not be verified via contacts after resolving the OOBI.".formatted(aid)));
            }
        } catch (Exception e) {
            log.warn("OOBI resolve failed for user {} ({}): {}", userId, oobiUrl, e.getMessage());
            return Either.left(invalid("Failed to resolve OOBI URL: %s".formatted(e.getMessage())));
        }
        return Either.right(null);
    }

    // --- persist the identity link (design §4.7) ---

    private Either<ProblemDetail, String> persistLink(String userId, String aid, String oobiUrl, boolean relink) {
        Optional<KeriIdentityLinkEntity> existing = identityLinkRepository.findById(userId);
        if (existing.isEmpty()) {
            KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
            link.setUserId(userId);
            link.setBindingVersion(1);
            link.setAid(aid);
            link.setOobiUrl(oobiUrl);
            identityLinkRepository.save(link);
            return Either.right(aid);
        }

        KeriIdentityLinkEntity link = existing.get();
        if (aid.equals(link.getAid())) {
            link.setOobiUrl(oobiUrl);
            identityLinkRepository.save(link);
            return Either.right(aid);
        }

        if (!relink) {
            return Either.left(KeriAttestationProblems.conflict(KeriAttestationProblems.IDENTITY_RELINKED,
                    "User %s is already linked to AID %s; retry with relink=true to switch to %s."
                            .formatted(userId, link.getAid(), aid)));
        }

        link.setBindingVersion(link.getBindingVersion() + 1);
        link.setCredentialSaid(null);
        link.setCredentialSchemaSaid(null);
        link.setAuthBeginTxHash(null);
        link.setAuthBeginBlock(null);
        link.setAuthBeginAt(null);
        link.setAid(aid);
        link.setOobiUrl(oobiUrl);
        identityLinkRepository.save(link);

        invalidateOpenCeremonies(userId);
        return Either.right(aid);
    }

    /** Fails every one of the user's non-terminal ceremonies on relink (design §4.7) — each was
     *  created under the old {@code bindingVersion} and can never be legitimately consumed once the
     *  identity behind it has changed. Direct mutation + save rather than
     *  {@link CeremonyService}'s step-CAS methods: those exist for async workers racing a retry, not
     *  for this bulk invalidation. {@code findByUserIdAndStateNotIn} is an unlocked discovery read
     *  though (same as {@code CeremonyCleanupJob}'s sweep), so each candidate is re-fetched under
     *  {@link KeriAttestationCeremonyRepository#findByIdForUpdate} and re-checked before being
     *  mutated — otherwise a concurrent legitimate transition (e.g. {@link CeremonyService
     *  #validateAndConsume} finishing the same ceremony between the discovery read and this write)
     *  could be silently clobbered back to FAILED. */
    private void invalidateOpenCeremonies(String userId) {
        List<KeriAttestationCeremonyEntity> candidates = ceremonyRepository.findByUserIdAndStateNotIn(userId, TERMINAL_STATES);
        for (KeriAttestationCeremonyEntity candidate : candidates) {
            ceremonyRepository.findByIdForUpdate(candidate.getId()).ifPresent(ceremony -> {
                if (TERMINAL_STATES.contains(ceremony.getState())) {
                    return;
                }
                ceremony.setState(CeremonyState.FAILED);
                ceremony.setErrorTitle(KeriAttestationProblems.IDENTITY_RELINKED);
                ceremony.setErrorDetail("Identity for user %s was relinked to a different AID.".formatted(userId));
                ceremonyRepository.save(ceremony);
            });
        }
    }

    private static ProblemDetail invalid(String detail) {
        return KeriAttestationProblems.unprocessable(KeriAttestationProblems.OOBI_INVALID, detail);
    }
}
