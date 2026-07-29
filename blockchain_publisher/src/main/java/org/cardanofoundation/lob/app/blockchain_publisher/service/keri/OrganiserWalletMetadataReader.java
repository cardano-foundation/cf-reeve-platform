package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.model.metadata.MetadataJSONContent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.keri_attestation.service.CardanoMetadataReader;

/**
 * Implements {@code keri_attestation}'s {@link CardanoMetadataReader} with the backend this module
 * already owns. Read-only: AUTH_BEGIN transactions are built and submitted through the normal
 * publishing pipeline ({@code AuthBeginPublishable}), not through this class.
 */
@Slf4j
@RequiredArgsConstructor
public class OrganiserWalletMetadataReader implements CardanoMetadataReader {

    /** CIP-170 metadata label, as the backend's metadata-service label string. */
    private static final String CIP170_LABEL = "170";

    private final BackendService backendService;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Map<String, Object>> readCip170Metadata(String txHash) {
        try {
            Result<List<MetadataJSONContent>> result = backendService.getMetadataService().getJSONMetadataByTxnHash(txHash);
            if (!result.isSuccessful() || result.getValue() == null) {
                return Optional.empty();
            }

            return result.getValue().stream()
                    .filter(content -> CIP170_LABEL.equals(content.getLabel()))
                    .findFirst()
                    .map(content -> objectMapper.convertValue(content.getJsonMetadata(), new TypeReference<Map<String, Object>>() { }));
        } catch (ApiException e) {
            log.warn("Error while reading CIP-170 metadata, txHash:{}", txHash, e);

            return Optional.empty();
        }
    }

}
