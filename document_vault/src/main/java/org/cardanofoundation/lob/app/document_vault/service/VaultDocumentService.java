package org.cardanofoundation.lob.app.document_vault.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B3: the client delivers exactly two crypto outputs (ciphertext + slots); the server
 * assigns the ID, content-addresses, persists and indexes. Blueprint B5/I5: nothing in here may
 * decrypt, unwrap or otherwise process secret material — validation is structural only.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VaultDocumentService {

    /**
     * Blueprint I7 posture: this is a SET, not a single value. When envelope v2 ships, 2 is added
     * and 1 stays — old client versions must keep being accepted. Unknown (future) versions are
     * rejected: a server cannot store an envelope schema it does not know.
     */
    public static final Set<Integer> SUPPORTED_ENVELOPE_VERSIONS = Set.of(1);

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyRepository keyRepository;
    private final KeycloakSecurityHelper securityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final ApplicationEventPublisher eventPublisher;
    /** Used at upload to reject slots wrapped to a key whose issuer has been de-trusted (§2.8.5). */
    private final KeyCardVerifier cardVerifier;

    @Value("${lob.document_vault.max-document-bytes:10485760}")
    private long maxDocumentBytes;

    @Value("${lob.document_vault.max-slots:64}")
    private int maxSlots;

    public Either<ProblemDetail, DocumentUploadedView> upload(UploadDocumentRequest request) {
        String organisationId = request.getOrganisationId();
        if (!securityHelper.canUserAccessOrg(organisationId)) {
            return Either.left(VaultProblems.forbidden(
                    "Current user is not a member of organisation %s.".formatted(organisationId)));
        }
        // Structural checks (no external call) go first, cheapest-first: a malformed envelope
        // should not cost an organisation lookup before it is rejected.
        if (!SUPPORTED_ENVELOPE_VERSIONS.contains(request.getEnvelopeVersion())) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.UNSUPPORTED_ENVELOPE_VERSION,
                    "Envelope version %d is not supported; supported versions: %s."
                            .formatted(request.getEnvelopeVersion(), SUPPORTED_ENVELOPE_VERSIONS)));
        }
        if (request.getSlots().size() > maxSlots) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.TOO_MANY_SLOTS,
                    "Envelope has %d slots; the maximum is %d.".formatted(request.getSlots().size(), maxSlots)));
        }

        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(request.getPayload().getCiphertext());
        } catch (IllegalArgumentException e) {
            return Either.left(VaultProblems.badRequest(VaultProblems.INVALID_PAYLOAD,
                    "ciphertext is not valid base64."));
        }
        if (ciphertext.length == 0) {
            return Either.left(VaultProblems.badRequest(VaultProblems.INVALID_PAYLOAD, "ciphertext is empty."));
        }
        if (ciphertext.length > maxDocumentBytes) {
            return Either.left(VaultProblems.payloadTooLarge(
                    "Ciphertext exceeds the maximum of %d bytes.".formatted(maxDocumentBytes)));
        }

        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Either.left(VaultProblems.notFound(VaultProblems.ORGANISATION_NOT_FOUND,
                    "Organisation %s does not exist.".formatted(organisationId)));
        }

        List<String> keyIds = request.getSlots().stream().map(UploadDocumentRequest.SlotRequest::getKeyId).toList();
        Map<String, VaultKeyEntity> keysById = keyRepository.findAllById(keyIds).stream()
                .collect(Collectors.toMap(VaultKeyEntity::getId, Function.identity()));
        for (UploadDocumentRequest.SlotRequest slot : request.getSlots()) {
            VaultKeyEntity key = keysById.get(slot.getKeyId());
            if (key == null || !key.getOrganisationId().equals(organisationId)) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s is unknown or not registered in organisation %s."
                                .formatted(slot.getKeyId(), organisationId)));
            }
            // Closes the stale-client window in the issuer containment (contract §2.8.5): a client that
            // cached the addressbook BEFORE an issuer was de-trusted would otherwise still upload a slot
            // wrapped to a key that issuer vouched for. Resolve is not an authorization gate and a
            // hostile client can put anything in a slot — but an HONEST client with stale state is the
            // likely case, and it costs one condition to stop it. Re-resolve and re-encrypt.
            if (!cardVerifier.isTrustedIssuer(key.getIssuerId())) {
                return Either.left(VaultProblems.unprocessable(VaultProblems.SLOT_KEY_INVALID,
                        "Slot key %s was vouched for by issuer %s, which is no longer trusted. "
                                .formatted(slot.getKeyId(), key.getIssuerId())
                                + "Re-resolve the recipients and encrypt again."));
            }
        }

        VaultDocumentEntity document = new VaultDocumentEntity();
        document.setId(UUID.randomUUID().toString());
        document.setOrganisationId(organisationId);
        document.setEnvelopeVersion(request.getEnvelopeVersion());
        document.setContentHash(sha256Hex(ciphertext));
        document.setPlaintextHash(request.getPlaintextHash());
        document.setCiphertext(ciphertext);
        document.setPayloadNonce(request.getPayload().getNonce());
        document.setFileName(request.getFileName());
        document.setContentType(request.getContentType());
        document.setDescription(request.getDescription());
        document.setSizeBytes(ciphertext.length);
        document.setCreatedByAccount(securityHelper.getCurrentUserId());
        document.setCreatedByName(securityHelper.getCurrentUser());
        document.setSlots(request.getSlots().stream()
                .map(slot -> new DocumentSlot(slot.getKeyId(), slot.getRecipientRef(),
                        slot.getEphemeralPub(), slot.getWrappedDek()))
                .toList());

        VaultDocumentEntity saved = documentRepository.save(document);

        Set<String> recipientAccountIds = request.getSlots().stream()
                .map(slot -> keysById.get(slot.getKeyId()).getAccountId())
                .collect(Collectors.toSet());
        eventPublisher.publishEvent(new DocumentSharedEvent(saved.getId(), organisationId, recipientAccountIds));

        return Either.right(new DocumentUploadedView(saved.getId(), saved.getContentHash(), saved.getCreatedAt()));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
