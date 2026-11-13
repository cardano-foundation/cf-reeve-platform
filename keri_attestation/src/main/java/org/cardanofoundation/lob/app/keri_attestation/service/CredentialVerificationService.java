package org.cardanofoundation.lob.app.keri_attestation.service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationStatus;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.CredentialVerificationEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CredentialAttestationView;
import org.cardanofoundation.lob.app.keri_attestation.repository.CredentialVerificationRepository;

/**
 * Records and serves this module's verdict on an imported card's credential.
 *
 * <p>The read side is what other modules use to show a badge; the write side is called once, by the
 * import path, immediately after every check has passed.
 *
 * <p><b>A verdict is never downgraded.</b> Re-importing the same card must not turn a verified row
 * into an unverified one, because that would let anyone holding a copy of somebody's card quietly
 * strip the badge off it by importing it again. A re-import that verifies refreshes the verdict; one
 * that does not, leaves the existing verdict alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialVerificationService {

    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    private final CredentialVerificationRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Records a passing verification for the card identified by {@code publicKey}.
     *
     * @param policyFingerprint identifies the policy that produced this verdict, so a later
     *                          configuration change does not silently rewrite what was decided.
     */
    @Transactional
    public void recordVerified(String organisationId, String publicKey, String attesterAid,
            CredentialChainValidator.ValidatedCredential credential, String kelSequence, String kelEventSaid,
            String policyFingerprint) {
        CredentialVerificationEntity row = repository
                .findByOrganisationIdAndPublicKey(organisationId, publicKey)
                .orElseGet(() -> {
                    CredentialVerificationEntity fresh = new CredentialVerificationEntity();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setOrganisationId(organisationId);
                    fresh.setPublicKey(publicKey);
                    return fresh;
                });

        row.setStatus(AttestationStatus.VERIFIED);
        row.setVerifiedAt(Instant.now());
        row.setAttesterAid(attesterAid);
        row.setCredentialSaid(credential.credentialSaid());
        row.setSchemaSaid(credential.schemaSaid());
        row.setSchemaName(credential.schemaName());
        row.setLeafIssuerAid(credential.leafIssuerAid());
        row.setTrustAnchorAid(credential.trustAnchorAid());
        row.setTrustModel(credential.trustModel());
        row.setKelSequence(kelSequence);
        row.setKelEventSaid(kelEventSaid);
        row.setPolicyFingerprint(policyFingerprint);
        row.setClaims(writeClaims(credential.claims()));
        repository.save(row);
    }

    /** @return the verdict for this card, or empty when it has never been verified. */
    @Transactional(readOnly = true)
    public Optional<CredentialAttestationView> find(String organisationId, String publicKey) {
        return repository.findByOrganisationIdAndPublicKey(organisationId, publicKey).map(this::toView);
    }

    /** Bulk read for list rendering, keyed by public key. One query, not one per row. */
    @Transactional(readOnly = true)
    public Map<String, CredentialAttestationView> findAll(String organisationId, Collection<String> publicKeys) {
        if (publicKeys == null || publicKeys.isEmpty()) {
            return Map.of();
        }
        List<CredentialVerificationEntity> rows =
                repository.findByOrganisationIdAndPublicKeyIn(organisationId, publicKeys);
        Map<String, CredentialAttestationView> byKey = new LinkedHashMap<>();
        for (CredentialVerificationEntity row : rows) {
            byKey.put(row.getPublicKey(), toView(row));
        }
        return byKey;
    }

    private CredentialAttestationView toView(CredentialVerificationEntity row) {
        return new CredentialAttestationView(row.getStatus(), row.getVerifiedAt(), row.getAttesterAid(),
                row.getSchemaSaid(), row.getSchemaName(), row.getLeafIssuerAid(), row.getTrustAnchorAid(),
                row.getTrustModel(), row.getKelSequence(), row.getKelEventSaid(), readClaims(row));
    }

    private String writeClaims(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(claims);
        } catch (Exception e) {
            // Claims are display-only. Losing them must not fail an import whose checks all passed.
            log.warn("Could not serialise credential claims, storing none: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> readClaims(CredentialVerificationEntity row) {
        if (row.getClaims() == null || row.getClaims().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(row.getClaims(), CLAIMS_TYPE);
        } catch (Exception e) {
            log.warn("Could not read stored credential claims for {}: {}", row.getId(), e.getMessage());
            return Map.of();
        }
    }
}
