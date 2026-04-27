package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockfrostIpfsResponse;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;

@RequiredArgsConstructor
@Slf4j
@Service
@ConditionalOnProperty(prefix = "lob.blockchain_publisher.ipfs.blockfrost", value = "enabled", havingValue = "true", matchIfMissing = false)
public class BlockfrostPublisher implements IpfsPublisher {

    @Value("${lob.blockchain_publisher.ipfs.blockfrost.url}")
    private String blockfrostUrl;

    @Value("${lob.blockchain_publisher.ipfs.blockfrost.project_id}")
    private String blockfrostProjectId;

    private final HttpClient httpClient;

    public BlockfrostPublisher() {
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public Either<ProblemDetail, String> publish(String content) {
        String boundary = "----JavaBoundary" + UUID.randomUUID();
        // Build multipart body
        String partHeader =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"reeve.json\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n";

        String endBoundary = "\r\n--" + boundary + "--\r\n";

        byte[] body = concat(
                partHeader.getBytes(),
                content.getBytes(),
                endBoundary.getBytes()
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(blockfrostUrl))
                .header("project_id", blockfrostProjectId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = null;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            problemDetail.setTitle("Error sending request to Blockfrost IPFS");
            log.error("Error sending request to Blockfrost IPFS: {}", e.getMessage());
            return Either.left(problemDetail);
        }
        ObjectMapper mapper = new ObjectMapper();
        BlockfrostIpfsResponse responseObject = null;
        try {
            responseObject = mapper.readValue(response.body(), BlockfrostIpfsResponse.class);
        } catch (JsonProcessingException e) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            problemDetail.setTitle("Error parsing Blockfrost IPFS response");
            log.error("Error parsing Blockfrost IPFS response: {}", e.getMessage());
            return Either.left(problemDetail);
        }
        return Either.right(responseObject.getIpfsHash());
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
