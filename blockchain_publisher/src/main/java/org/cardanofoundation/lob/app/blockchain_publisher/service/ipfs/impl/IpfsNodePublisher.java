package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.impl;

import java.io.IOException;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "lob.blockchain_publisher.ipfs.local", value = "enabled", havingValue = "true", matchIfMissing = false)
public class IpfsNodePublisher implements IpfsPublisher {

    private IPFS ipfs;

    @Value("${lob.blockchain_publisher.ipfs.local.node}")
    private String node;

    // package-private for testing
    void setIpfs(IPFS ipfs) {
        this.ipfs = ipfs;
    }

    @PostConstruct
    public void init() {
        String[] parts = node.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 5001;
        try {
            ipfs = new IPFS(host, port);
            log.info("Connected to local IPFS node at {}:{}", host, port);
        } catch (Exception e) {
            log.warn("Could not connect to local IPFS node at {}:{} – local IPFS will be unavailable. Cause: {}",
                    host, port, e.getMessage());
            throw new RuntimeException("Could not connect to local IPFS node at " + host + ":" + port, e);
        }
    }

    @Override
    public Either<ProblemDetail, String> publish(String content) {
        try {
            // Convert string to a streamable format for IPFS
            NamedStreamable.ByteArrayWrapper file = new NamedStreamable.ByteArrayWrapper(content.getBytes());

            // Add the content to IPFS
            MerkleNode response = ipfs.add(file).get(0);

            // Return the CID (Hash)
            String base58 = response.hash.toBase58();
            log.info("Content stored in IPFS with CID: {}", base58);
            return Either.right(base58);

        } catch (IOException e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error while saving to IPFS: " + e.getMessage());
            problemDetail.setTitle("Error while saving to IPFS");
            return Either.left(problemDetail);
        }
    }
}
