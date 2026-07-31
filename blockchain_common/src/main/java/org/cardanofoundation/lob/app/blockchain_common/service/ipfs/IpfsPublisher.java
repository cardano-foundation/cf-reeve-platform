package org.cardanofoundation.lob.app.blockchain_common.service.ipfs;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

/**
 * Writes an envelope to IPFS and names it by its content.
 *
 * <p>Lives in {@code blockchain_common} because BOTH tiers need it and neither may depend on the
 * other. The publisher pins for real at dispatch; the vault only needs {@link #contentId} during an
 * attestation ceremony, so that the wallet can commit to a manifest carrying the CID the publisher
 * will later produce. Before that split the ceremony could not know the CID at all, and had to attest
 * a side-structure instead — which nothing on chain could be checked against.
 */
public interface IpfsPublisher {

    /**
     * Stores {@code content} DURABLY — added and pinned — and returns its CID.
     *
     * @return the CID of the published content.
     */
    Either<ProblemDetail, String> publish(String content);

    /**
     * Returns the CID {@code content} will have, WITHOUT storing it durably.
     *
     * <p>Used during an attestation ceremony, which must not leave anything behind if the ceremony is
     * abandoned. Implementations must not pin here: a CID is a pure function of the bytes, so this is
     * a naming operation, not a write.
     *
     * <p>Critically, the CID must be produced by the same implementation that will later
     * {@link #publish} — computing it locally would mean reimplementing that implementation's UnixFS
     * chunking and DAG layout bit-exactly, and any drift would surface only at dispatch, as an
     * attested manifest that no longer matches the published one.
     */
    Either<ProblemDetail, String> contentId(String content);
}
