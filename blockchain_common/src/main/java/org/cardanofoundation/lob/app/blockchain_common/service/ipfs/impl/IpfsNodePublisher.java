package org.cardanofoundation.lob.app.blockchain_common.service.ipfs.impl;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;

/**
 * A kubo node reached over its HTTP API.
 *
 * <p>{@link #contentId} uses the node's own {@code only-hash} mode: it runs the identical UnixFS
 * chunking and DAG construction a real add would, then discards the blocks instead of storing them.
 * So the CID is guaranteed to equal the one {@link #publish} later produces for the same bytes, and an
 * abandoned attestation ceremony leaves nothing on the node to clean up.
 *
 * <p>That guarantee is the whole reason this is not computed in-process: reproducing it locally means
 * reimplementing the node's chunker and DAG layout bit-exactly, for inputs of unbounded size, against
 * a node whose settings this codebase does not control.
 */
@Slf4j
public class IpfsNodePublisher implements IpfsPublisher {

    private final IPFS ipfs;

    public IpfsNodePublisher(String node) {
        String[] parts = node.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 5001;
        try {
            this.ipfs = new IPFS(host, port);
        } catch (Exception e) {
            log.warn("Could not connect to local IPFS node at {}:{} – local IPFS will be unavailable. Cause: {}",
                    host, port, e.getMessage());
            throw new IllegalStateException("Could not connect to local IPFS node at " + host + ":" + port, e);
        }
        log.info("Connected to local IPFS node at {}:{}", host, port);
    }

    // package-private for testing: bypasses the connect in the public constructor.
    IpfsNodePublisher(IPFS ipfs) {
        this.ipfs = ipfs;
    }

    @Override
    public Either<ProblemDetail, String> publish(String content) {
        // kubo's add pins by default, so this stores durably.
        return add(content, false);
    }

    @Override
    public Either<ProblemDetail, String> contentId(String content) {
        return add(content, true);
    }

    private Either<ProblemDetail, String> add(String content, boolean hashOnly) {
        try {
            NamedStreamable.ByteArrayWrapper file = new NamedStreamable.ByteArrayWrapper(content.getBytes());

            // (file, wrapWithDirectory, onlyHash) — onlyHash maps to the node's own only-hash flag, which
            // computes the CID and writes nothing.
            MerkleNode response = ipfs.add(file, false, hashOnly).get(0);

            String base58 = response.hash.toBase58();
            if (hashOnly) {
                log.info("Computed IPFS CID {} without storing", base58);
            } else {
                log.info("Content stored in IPFS with CID: {}", base58);
            }

            return Either.right(base58);
        } catch (IOException e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error while saving to IPFS: " + e.getMessage());
            problemDetail.setTitle("Error while saving to IPFS");

            return Either.left(problemDetail);
        }
    }
}
