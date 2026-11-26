package org.cardanofoundation.lob.app.blockchain_common.service.ipfs.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;

/**
 * Blockfrost's hosted IPFS.
 *
 * <p>Blockfrost splits storing from keeping: {@code /ipfs/add} uploads the object and returns its CID
 * but does NOT pin it, and unpinned objects are garbage-collected. {@code /ipfs/pin/add/{cid}} is what
 * makes it durable. That split is exactly what this port needs — {@link #contentId} is a bare add,
 * so an abandoned ceremony expires on its own with no compensating unpin to get wrong, while
 * {@link #publish} adds AND pins.
 *
 * <p>The pin call was missing entirely before this: every document was added and never pinned, so
 * published envelopes were eligible for garbage collection the whole time.
 */
@Slf4j
public class BlockfrostPublisher implements IpfsPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The configured {@code .../ipfs/add} endpoint. */
    private final String addUrl;
    private final String projectId;
    private final HttpClient httpClient;

    public BlockfrostPublisher(String addUrl, String projectId) {
        this(addUrl, projectId, HttpClient.newHttpClient());
    }

    // package-private for testing
    BlockfrostPublisher(String addUrl, String projectId, HttpClient httpClient) {
        this.addUrl = addUrl;
        this.projectId = projectId;
        this.httpClient = httpClient;
    }

    @Override
    public Either<ProblemDetail, String> contentId(String content) {
        return add(content);
    }

    @Override
    public Either<ProblemDetail, String> publish(String content) {
        return add(content).flatMap(cid -> pin(cid).map(ignored -> cid));
    }

    // --- internals ---

    private Either<ProblemDetail, String> add(String content) {
        String boundary = "----JavaBoundary" + UUID.randomUUID();
        String partHeader =
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"reeve.json\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n";
        String endBoundary = "\r\n--" + boundary + "--\r\n";

        byte[] body = concat(partHeader.getBytes(), content.getBytes(), endBoundary.getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(addUrl))
                .header("project_id", projectId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        Either<ProblemDetail, HttpResponse<String>> sent = send(request, "upload");
        if (sent.isLeft()) {
            return Either.left(sent.getLeft());
        }
        HttpResponse<String> response = sent.get();

        BlockfrostIpfsResponse responseObject;
        try {
            responseObject = MAPPER.readValue(response.body(), BlockfrostIpfsResponse.class);
        } catch (JsonProcessingException e) {
            return Either.left(problem("Error parsing Blockfrost IPFS response", e.getMessage()));
        }

        // A 2xx with no ipfs_hash is not a success: it would anchor an L1 manifest with a null CID.
        if (responseObject.getIpfsHash() == null || responseObject.getIpfsHash().isBlank()) {
            return Either.left(problem("Blockfrost IPFS response missing ipfs_hash",
                    "Blockfrost IPFS response contained no ipfs_hash: %s".formatted(response.body())));
        }

        return Either.right(responseObject.getIpfsHash());
    }

    /**
     * Pins {@code cid} so Blockfrost stops treating it as collectable.
     *
     * <p>Idempotent by construction — pinning an already-pinned CID is a no-op on Blockfrost's side —
     * so a retried dispatch is safe.
     */
    private Either<ProblemDetail, String> pin(String cid) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pinUrl(cid)))
                .header("project_id", projectId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return send(request, "pin").map(HttpResponse::body);
    }

    /**
     * Derives {@code .../ipfs/pin/add/{cid}} from the configured {@code .../ipfs/add} endpoint.
     *
     * <p>Derived rather than separately configured so the two endpoints cannot be pointed at different
     * Blockfrost environments by a partial config change.
     */
    String pinUrl(String cid) {
        if (!addUrl.endsWith("/add")) {
            throw new IllegalStateException(
                    "The Blockfrost IPFS URL is expected to end in /add so the pin endpoint can be derived from it, but it is: "
                            + addUrl);
        }
        return addUrl.substring(0, addUrl.length() - "/add".length()) + "/pin/add/" + cid;
    }

    private Either<ProblemDetail, HttpResponse<String>> send(HttpRequest request, String what) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Error sending IPFS {} request to Blockfrost: {}", what, e.getMessage());
            return Either.left(problem("Error sending request to Blockfrost IPFS", e.getMessage()));
        }

        // Blockfrost reports failures as a non-2xx status with a JSON error envelope. Surface that body
        // verbatim; feeding it to the success parser would hide the cause behind an opaque Jackson
        // "Unrecognized field" message.
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            log.error("Blockfrost IPFS {} rejected (HTTP {}): {}", what, statusCode, response.body());
            return Either.left(problem("Blockfrost IPFS %s rejected".formatted(what),
                    "Blockfrost IPFS %s failed with HTTP %d: %s".formatted(what, statusCode, response.body())));
        }

        return Either.right(response);
    }

    private static ProblemDetail problem(String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, detail);
        problemDetail.setTitle(title);

        return problemDetail;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }

        byte[] result = new byte[length];
        int pos = 0;

        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }

        return result;
    }
}
