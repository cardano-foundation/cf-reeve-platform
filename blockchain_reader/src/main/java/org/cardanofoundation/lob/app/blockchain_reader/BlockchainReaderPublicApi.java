package org.cardanofoundation.lob.app.blockchain_reader;

import static org.zalando.problem.Status.BAD_REQUEST;
import static org.zalando.problem.Status.INTERNAL_SERVER_ERROR;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.blockchain_common.domain.CardanoNetwork;
import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.domain.OnChainTxDetails;
import org.cardanofoundation.lob.app.blockchain_reader.domain.LOBOnChainTxStatusRequest;
import org.cardanofoundation.lob.app.blockchain_reader.domain.LOBOnChainTxStatusResponse;

@RequiredArgsConstructor
@Slf4j
public class BlockchainReaderPublicApi implements BlockchainReaderPublicApiIF {

    private final RestClient restClient;
    private final CardanoNetwork network;

    @Value("${lob.blockchain_reader.lob_follower_base_url:http://localhost:9090/api}")
    private String lobFollowerBaseUrl;

    @PostConstruct
    public void init() {
        log.info("BlockchainReaderPublicApi initialized with network: {}", network);
    }

    @Override
    public Either<Problem, ChainTip> getChainTip() {
        try {
            ChainTip chainTip = restClient.get()
                    .uri("%s/tip".formatted(lobFollowerBaseUrl))
                    .retrieve()
                    .body(ChainTip.class);

            if (chainTip.getNetwork() != network) {
                ThrowableProblem problem = Problem.builder()
                        .withTitle("NETWORK_MISMATCH")
                        .withStatus(BAD_REQUEST)
                        .withDetail("Network mismatch: %s != %s".formatted(chainTip.getNetwork(), network))
                        .build();

                return Either.left(problem);
            }

            return Either.right(chainTip);
        } catch (RestClientResponseException ex) {
            ThrowableProblem problem = Problem.builder()
                    .withTitle("CHAIN_TIP_ERROR")
                    .withStatus(BAD_REQUEST)
                    .withDetail("Error from the client: %s".formatted(ex.getResponseBodyAsString()))
                    .build();
            return Either.left(problem);  // Return as Either.left
        } catch (RestClientException ex) {
            log.error("Error while fetching chain tip", ex);
            ThrowableProblem problem = Problem.builder()
                    .withTitle("CHAIN_TIP_ERROR")
                    .withStatus(INTERNAL_SERVER_ERROR)
                    .withDetail("Internal server error, reason: %s".formatted(ex.getMessage()))
                    .build();

            return Either.left(problem);
        }
    }

    @Override
    public Either<Problem, Optional<OnChainTxDetails>> getTxDetails(String transactionHash) {
        try {
            OnChainTxDetails txDetails = restClient.get()
                    .uri("%s/tx-details/%s".formatted(lobFollowerBaseUrl, transactionHash))
                    .retrieve()
                    .body(OnChainTxDetails.class);

            if (txDetails.getNetwork() != network) {
                ThrowableProblem problem = Problem.builder()
                        .withTitle("NETWORK_MISMATCH")
                        .withStatus(BAD_REQUEST)
                        .withDetail("Network mismatch: %s != %s".formatted(txDetails.getNetwork(), network))
                        .build();

                return Either.left(problem);
            }

            return Either.right(Optional.of(txDetails));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                String responseText = ex.getResponseBodyAsString();

                if (responseText.contains("TX_NOT_FOUND")) { // let's really check that lob-follower returned TX_NOT_FOUND in the response to avoid misconfiguration of a firewall / load balancer
                    return Either.right(Optional.empty());
                }
            }

            ThrowableProblem problem = Problem.builder()
                    .withTitle("TX_DETAILS_ERROR")
                    .withStatus(BAD_REQUEST)
                    .withDetail("Error from the client: %s".formatted(ex.getResponseBodyAsString()))
                    .build();

            return Either.left(problem);  // Return as Either.left
        } catch (RestClientException ex) {
            log.error("Error while fetching tx details", ex);

            ThrowableProblem problem = Problem.builder()
                    .withTitle("TX_DETAILS_ERROR")
                    .withStatus(INTERNAL_SERVER_ERROR)
                    .withDetail("Internal server error, reason: %s".formatted(ex.getMessage()))
                    .build();

            return Either.left(problem);
        }
    }

    @Override
    public Either<Problem, Map<String, Boolean>> isOnChain(Set<String> transactionIds) {
        try {
            LOBOnChainTxStatusResponse lobOnChainDetailsResponse = restClient.post()
                    .uri("%s/on-chain-statuses".formatted(lobFollowerBaseUrl))
                    .body(new LOBOnChainTxStatusRequest(transactionIds))
                    .retrieve()
                    .body(LOBOnChainTxStatusResponse.class);

            if (lobOnChainDetailsResponse.getNetwork() != network) {
                ThrowableProblem problem = Problem.builder()
                        .withTitle("NETWORK_MISMATCH")
                        .withStatus(BAD_REQUEST)
                        .withDetail("Network mismatch: %s != %s".formatted(lobOnChainDetailsResponse.getNetwork(), network))
                        .build();

                return Either.left(problem);
            }

            return Either.right(lobOnChainDetailsResponse.getTransactionStatuses());
        } catch (RestClientResponseException ex) {
            ThrowableProblem problem = Problem.builder()
                    .withTitle("LOB_TX_STATUSES_ERROR")
                    .withStatus(BAD_REQUEST)
                    .withDetail("Error from the client: %s".formatted(ex.getResponseBodyAsString()))
                    .build();

            return Either.left(problem);  // Return as Either.left
        } catch (RestClientException ex) {
            log.error("Error while fetching on-chain statuses", ex);

            ThrowableProblem problem = Problem.builder()
                    .withTitle("LOB_TX_STATUSES_ERROR")
                    .withStatus(INTERNAL_SERVER_ERROR)
                    .withDetail("Internal server error, reason: %s".formatted(ex.getMessage()))
                    .build();

            return Either.left(problem);
        }
    }

}
