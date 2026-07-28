package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import io.vavr.control.Either;

/**
 * Port implemented by the host module that owns the Cardano wallet used to publish AUTH_BEGIN
 * transactions and to observe their confirmation. Implemented by {@code blockchain_publisher} using
 * the organiser account and backend it already owns.
 */
public interface CardanoMetadataTxSubmitter {
    /** Builds, signs and submits a tx carrying only the given metadata. Returns tx hash. */
    Either<ProblemDetail, String> submitMetadataTransaction(long label, MetadataMap metadata);

    /** Confirmation depth of a tx, empty if unknown/not found. */
    Optional<Long> confirmations(String txHash);

    /** Reads label-170 metadata of an on-chain tx (for EXTERNAL authority verification). */
    Optional<Map<String, Object>> readCip170Metadata(String txHash);
}
