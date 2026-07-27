package org.cardanofoundation.lob.app.document_vault.service;

import java.math.BigInteger;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;

/**
 * Recomputes an imported card's CIP-170 attestation digest — the platform (B2) side of the contract
 * defined by the indexer's {@code CardAttestationDigestFactory}
 * ({@code reeve-indexing-example}). The on-chain CIP-170 {@code ATTEST} metadata for the card's
 * {@code txHash} carries this value in its {@code d} field; verifying an imported card means
 * recomputing this digest from the card JSON and comparing it against that on-chain {@code d}.
 *
 * <p><b>The formula MUST byte-match the indexer's</b> (any divergence silently fails verification of
 * genuine cards): the Blake3-256 {@link Cip170MetadataFactory#digestOf} qb64 over the canonical CBOR
 * of a {@link MetadataMap} built from the card's REEVE_KEY_CARD fields <b>MINUS the {@code
 * attestation} block</b>, with the same "omit if blank" rule the card producer applies to
 * {@code displayName}/{@code email}/{@code label}:
 * <pre>
 * { v, type, subject:{subjectType, subjectId, [displayName], [email], organisationId},
 *   key:{publicKey, [label], assurance, createdAt} }
 * </pre>
 * The enum wire strings match: {@code CardSubjectType}/{@code KeyAssurance} have no {@code @JsonValue},
 * so their Jackson wire form is {@code name()} — identical to the indexer's stored string columns.
 * Insertion order is irrelevant (canonical CBOR sorts keys). Cross-checked against the indexer's
 * golden vector in {@code CardAttestationDigestFactoryTest}.
 */
@Service
@RequiredArgsConstructor
public class CardAttestationDigestFactory {

    // Lazily resolved. Cip170MetadataFactory now lives in blockchain_common, declared as an ungated
    // @Bean in BlockchainCommonConfig, so it is no longer tied to lob.keri-attestation.enabled and is
    // expected to be present. The ObjectProvider is kept anyway: digestOf is only ever reached from the
    // attestation-verification path, which already requires keri_attestation, and CardImportService
    // rejects an attested card when AttestationImportVerifier is absent before this is ever called. So
    // failing lazily here remains the right behaviour, just no longer the expected one.
    private final ObjectProvider<Cip170MetadataFactory> cip170MetadataFactoryProvider;

    /** @return the imported card's canonical attestation digest (qb64, CESR-prefixed 'E'). */
    public String digestOf(KeyCardDto card) {
        return cip170MetadataFactoryProvider.getObject().digestOf(cardMetadataMap(card));
    }

    private static MetadataMap cardMetadataMap(KeyCardDto card) {
        MetadataMap root = MetadataBuilder.createMap();
        root.put("v", BigInteger.valueOf(card.getV()));
        root.put("type", card.getType());

        KeyCardDto.Subject subject = card.getSubject();
        MetadataMap subjectMap = MetadataBuilder.createMap();
        subjectMap.put("subjectType", subject.subjectType().name());
        subjectMap.put("subjectId", subject.subjectId());
        putIfPresent(subjectMap, "displayName", subject.displayName());
        putIfPresent(subjectMap, "email", subject.email());
        // Mirror the indexer exactly: organisationId is put unconditionally (never omitted).
        subjectMap.put("organisationId", subject.organisationId());
        root.put("subject", subjectMap);

        KeyCardDto.Key key = card.getKey();
        MetadataMap keyMap = MetadataBuilder.createMap();
        keyMap.put("publicKey", key.publicKey());
        putIfPresent(keyMap, "label", key.label());
        keyMap.put("assurance", key.assurance().name());
        keyMap.put("createdAt", key.createdAt());
        root.put("key", keyMap);

        return root;
    }

    private static void putIfPresent(MetadataMap map, String field, String value) {
        if (value != null && !value.isBlank()) {
            map.put(field, value);
        }
    }
}
