package org.cardanofoundation.lob.app.keri_attestation.resource;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.AuthBeginRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.CreateCeremonyRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.ResolveOobiRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.request.StepRetryRequest;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.AgentOobiView;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.IdentityView;
import org.cardanofoundation.lob.app.keri_attestation.repository.KeriIdentityLinkRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CeremonyService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAgentService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAttestService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriAuthBeginService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriCredentialService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriOobiService;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * REST surface for the KERI attestation module: identity linking, the platform agent's
 * own OOBI, and the ceremony lifecycle (create, the three step POSTs, and polling).
 *
 * <p>Gated the same way its collaborator services are ({@code lob.keri-attestation.keria.url}
 * configured), a narrower condition than the module's own {@code lob.keri-attestation.enabled} flag —
 * see {@code SignifyClientConfig}'s javadoc for why. In practice a real deployment always configures
 * both together; the narrower gate exists so this class's constructor (which wires every
 * {@code keria}-conditional service directly, deliberately, so the controller itself stays thin with
 * no null-checking) never fails to find a bean. A request to any path here 404s at the dispatcher —
 * before this class is ever involved — whenever either flag is off, which is exactly the "is the module
 * up" probe the frontend needs; a user who simply hasn't linked their identity yet still gets a normal
 * {@code 200} from {@link #identity()} with {@code linked=false}.
 */
@RestController
@RequestMapping("/api/v1/keri-attestation")
@Tag(name = "KERI Attestation",
        description = "Wallet-signed KERI identity linking, credential presentation, and AUTH_BEGIN/ATTEST anchoring ceremonies")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriAttestationController {

    /**
     * Mirrors {@code VaultDocumentController#publish}'s SpEL exactly (per the brief): every endpoint
     * here either anchors on-chain (AUTH_BEGIN, ATTEST) or feeds into a ceremony that will, so the same
     * manager-or-admin-only separation of duties applies uniformly rather than singling out the
     * anchoring steps alone.
     */
    private static final String PUBLISH_ROLES =
            "hasRole(@securityConfig.getManagerRole()) or hasRole(@securityConfig.getAdminRole())";

    private final KeycloakSecurityHelper securityHelper;
    private final CeremonyService ceremonyService;
    private final KeriOobiService oobiService;
    private final KeriAgentService agentService;
    private final KeriCredentialService credentialService;
    private final KeriAuthBeginService authBeginService;
    private final KeriAttestService attestService;
    private final KeriIdentityLinkRepository identityLinkRepository;

    private record AidView(String aid) {
    }

    private record ResetView(boolean reset) {
    }

    @Operation(description = "The current user's KERI identity-link status. linked=false (not 404) when never linked — the frontend uses this to decide whether to show the linking flow.")
    @GetMapping(value = "/identity", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<IdentityView> identity() {
        String userId = securityHelper.getCurrentUserId();
        IdentityView view = identityLinkRepository.findById(userId)
                .map(KeriAttestationController::toIdentityView)
                .orElseGet(() -> new IdentityView(false, null, null, null));
        return ResponseEntity.ok(view);
    }

    @Operation(description = "The platform's own KERI agent OOBI URL, for the wallet side of the OOBI exchange.")
    @GetMapping(value = "/agent/oobi", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<AgentOobiView> agentOobi() {
        return ResponseEntity.ok(new AgentOobiView(agentService.agentOobi()));
    }

    @Operation(description = "Resolve the user's wallet OOBI into an AID and create/update their identity link (design §4.7). relink=true is required to switch to a different AID once already linked.")
    @PostMapping(value = "/identity/oobi/resolve", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> resolveOobi(@Valid @RequestBody ResolveOobiRequest request) {
        String userId = securityHelper.getCurrentUserId();
        return Responses.respond(
                oobiService.resolveUserOobi(userId, request.getOobiUrl(), request.isRelink()).map(AidView::new),
                HttpStatus.OK);
    }

    @Operation(description = "Reset (fully unlink) the caller's KERI identity: deletes their identity link and fails every one of their non-terminal ceremonies. Idempotent — returns 200 even when the caller was never linked.")
    @DeleteMapping(value = "/identity", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> resetIdentity() {
        String userId = securityHelper.getCurrentUserId();
        return Responses.respond(oobiService.resetIdentity(userId).map(v -> new ResetView(true)), HttpStatus.OK);
    }

    @Operation(description = "Start a new attestation ceremony for a target (design §4.2). Fast-forwards past identity-level steps the user has already completed.")
    @PostMapping(value = "/ceremonies", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> createCeremony(@Valid @RequestBody CreateCeremonyRequest request) {
        String userId = securityHelper.getCurrentUserId();
        return Responses.respond(ceremonyService.create(userId, request.getTargetType(), request.getTargetId()),
                HttpStatus.CREATED);
    }

    @Operation(description = "Begin (or retry) the credential-presentation step: runs the IPEX apply/offer/agree/grant/admit exchange with the linked wallet synchronously and returns the final ceremony state.")
    @PostMapping(value = "/ceremonies/{id}/credential/request", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> requestCredential(@PathVariable String id,
            @RequestBody(required = false) StepRetryRequest request) {
        String userId = securityHelper.getCurrentUserId();
        boolean retry = request != null && request.isRetry();
        return Responses.respond(credentialService.presentCredential(id, userId, retry), HttpStatus.OK);
    }

    @Operation(description = "Begin (or retry) the AUTH_BEGIN step: either accepts an unverified 'already published' assertion, "
            + "or hands a fresh AUTH_BEGIN transaction to blockchain_publisher. Publication is asynchronous — the ceremony rests in "
            + "AUTH_BEGIN_SUBMITTED and reaches AUTH_BEGIN_CONFIRMED once the publisher reports the transaction dispatched.")
    @PostMapping(value = "/ceremonies/{id}/auth-begin", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> submitAuthBegin(@PathVariable String id,
            @RequestBody(required = false) AuthBeginRequest request) {
        String userId = securityHelper.getCurrentUserId();
        boolean assumePublished = request != null && request.isAssumePublished();
        boolean retry = request != null && request.isRetry();
        return Responses.respond(authBeginService.submitAuthBegin(id, userId, assumePublished, retry), HttpStatus.OK);
    }

    @Operation(description = "Begin (or retry) the ATTEST step: sends a remotesign anchoring request to the linked wallet and waits, synchronously, for the wallet's confirmed anchor, returning the final ceremony state.")
    @PostMapping(value = "/ceremonies/{id}/attest", produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> attest(@PathVariable String id,
            @RequestBody(required = false) StepRetryRequest request) {
        String userId = securityHelper.getCurrentUserId();
        boolean retry = request != null && request.isRetry();
        return Responses.respond(attestService.attest(id, userId, retry), HttpStatus.OK);
    }

    @Operation(description = "Fetch a ceremony's current state (design §4.2/§4.6). Useful for the wizard's step derivation without re-driving a step.")
    @GetMapping(value = "/ceremonies/{id}", produces = APPLICATION_JSON_VALUE)
    @PreAuthorize(PUBLISH_ROLES)
    public ResponseEntity<Object> getCeremony(@PathVariable String id) {
        String userId = securityHelper.getCurrentUserId();
        return Responses.respond(ceremonyService.get(id, userId), HttpStatus.OK);
    }

    // --- internals ---

    private static IdentityView toIdentityView(KeriIdentityLinkEntity link) {
        IdentityView.IdentityCredentialView credential = link.getCredentialSaid() == null ? null
                : new IdentityView.IdentityCredentialView(link.getCredentialSaid(), link.getCredentialSchemaSaid());
        // Asserted-but-unverified AUTH_BEGIN has no tx hash but still counts as complete (external=true),
        // so the identity panel reflects it the same way the ceremony skip logic does.
        IdentityView.AuthBeginView authBegin = (link.getAuthBeginTxHash() == null && !link.isAuthBeginAsserted()) ? null
                : new IdentityView.AuthBeginView(link.getAuthBeginTxHash(), link.getAuthBeginAt(), link.isAuthBeginAsserted());
        return new IdentityView(true, link.getAid(), credential, authBegin);
    }
}
