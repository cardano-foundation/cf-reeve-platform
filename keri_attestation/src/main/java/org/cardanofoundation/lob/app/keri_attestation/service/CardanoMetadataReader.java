package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Map;
import java.util.Optional;

/**
 * Read-only view of on-chain CIP-170 metadata, implemented by whichever module owns chain access
 * ({@code blockchain_publisher}).
 *
 * <p>Deliberately read-only: this module does not submit Cardano transactions. AUTH_BEGIN is
 * published by handing an {@code AuthBeginPublishCommand} to {@code blockchain_publisher}, which owns
 * the wallet and the dispatcher, so nothing here needs a submitter. The one remaining reason to look
 * at the chain from this module is verifying an imported card's existing ATTEST
 * ({@link AttestationImportVerifier}), which reads and never writes.
 *
 * <p>Absent in a deployment without {@code blockchain_publisher}; consumers inject it through an
 * {@code ObjectProvider} and fail closed when it is missing.
 */
public interface CardanoMetadataReader {

    /** Label-170 metadata of an on-chain tx, empty if the tx or the label is not found. */
    Optional<Map<String, Object>> readCip170Metadata(String txHash);
}
