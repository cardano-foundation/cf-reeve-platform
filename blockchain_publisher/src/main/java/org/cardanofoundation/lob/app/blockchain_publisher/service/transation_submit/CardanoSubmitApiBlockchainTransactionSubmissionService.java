package org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import org.cardanofoundation.lob.app.blockchain_common.BlockchainException;

@Slf4j
@RequiredArgsConstructor
public class CardanoSubmitApiBlockchainTransactionSubmissionService implements BlockchainTransactionSubmissionService {

    private final String cardanoSubmitApiUrl;
    private final String blockfrostApiKey;

    private final HttpClient httpClient;
    private final int timeoutInSeconds;

    @Override
    @SneakyThrows
    public String submitTransaction(byte[] txData) {
        HttpRequest txTransactionSubmitPostRequest = HttpRequest.newBuilder()
                .uri(URI.create(cardanoSubmitApiUrl))
                .POST(HttpRequest.BodyPublishers.ofByteArray(txData))
                .timeout(java.time.Duration.ofSeconds(timeoutInSeconds))
                .header("Content-Type", "application/cbor")
                .header("project_id", blockfrostApiKey)
                .build();

        HttpResponse<String> r = httpClient.send(txTransactionSubmitPostRequest, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 200 && r.statusCode() < 300) {
            String json = r.body();

            JsonNode jNode = JsonUtil.parseJson(json);
            String txId  = jNode.asText();

            return txId;
        }

        throw new BlockchainException("Error submitting transaction: %s to CardanoSubmitApi. Response: %s - %s".formatted(TransactionUtil.getTxHash(txData), r.statusCode(), r.body()));
    }

}
