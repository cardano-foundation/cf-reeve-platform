package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.OnChainTransactionSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.OnChainTransactionsPageResponse;

@Slf4j
@RequiredArgsConstructor
public class OnChainIndexerService {

    private static final String INDEXER_API_ERROR = "INDEXER_API_ERROR";
    private static final String TRANSACTIONS_BY_DATE_RANGE_PATH = "/api/v1/transactions/by-date-range";
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Getter
    private final String baseUrl;
    private final int pageSize;

    /**
     * Creates an OnChainIndexerService with default page size.
     */
    public static OnChainIndexerService withDefaultPageSize(ObjectMapper objectMapper, RestClient restClient, String baseUrl) {
        return new OnChainIndexerService(objectMapper, restClient, baseUrl, DEFAULT_PAGE_SIZE);
    }

    public Either<Problem, List<OnChainTransactionDto>> retrieveTransactionsByDateRange(
            String organisationId,
            LocalDate dateFrom,
            LocalDate dateTo) {

        log.info("Retrieving transactions from On-Chain Indexer for organisation: {}, dateFrom: {}, dateTo: {}",
                organisationId, dateFrom, dateTo);

        List<OnChainTransactionDto> allTransactions = new ArrayList<>();
        int currentPage = 0;
        boolean hasMorePages = true;

        while (hasMorePages) {
            Either<Problem, OnChainTransactionsPageResponse> pageResultE =
                    fetchPage(organisationId, dateFrom, dateTo, currentPage);

            if (pageResultE.isLeft()) {
                return Either.left(pageResultE.getLeft());
            }

            OnChainTransactionsPageResponse pageResponse = pageResultE.get();
            allTransactions.addAll(pageResponse.content());

            hasMorePages = !pageResponse.last();
            currentPage++;

            log.debug("\n\n\n###########\nComprobar si llama varias veces\n###########\nFetched page {} with {} transactions. Total so far: {}. Has more: {}",
                    currentPage - 1, pageResponse.content().size(), allTransactions.size(), hasMorePages);
        }

        log.info("Successfully retrieved {} transactions from On-Chain Indexer", allTransactions.size());
        return Either.right(allTransactions);
    }

    private Either<Problem, OnChainTransactionsPageResponse> fetchPage(
            String organisationId,
            LocalDate dateFrom,
            LocalDate dateTo,
            int page) {

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(TRANSACTIONS_BY_DATE_RANGE_PATH)
                .queryParam("page", page)
                .queryParam("size", pageSize)
                .toUriString();

        OnChainTransactionSearchRequest requestBody = new OnChainTransactionSearchRequest(
                organisationId,
                ISO_LOCAL_DATE.format(dateFrom),
                ISO_LOCAL_DATE.format(dateTo)
        );

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("Calling On-Chain Indexer API: {} with body: {}", url, requestJson);

            ResponseEntity<String> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .toEntity(String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                OnChainTransactionsPageResponse pageResponse =
                        objectMapper.readValue(response.getBody(), OnChainTransactionsPageResponse.class);
                return Either.right(pageResponse);
            }

            log.error("On-Chain Indexer API returned error status: {}, body: {}",
                    response.getStatusCode(), response.getBody());

            return Either.left(Problem.builder()
                    .withStatus(Status.valueOf(response.getStatusCode().value()))
                    .withTitle(INDEXER_API_ERROR)
                    .withDetail("Indexer API returned error: " + response.getBody())
                    .build());

        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON response from On-Chain Indexer API: {}", e.getMessage());
            return Either.left(Problem.builder()
                    .withStatus(Status.INTERNAL_SERVER_ERROR)
                    .withTitle(INDEXER_API_ERROR)
                    .withDetail("Error parsing JSON response: " + e.getMessage())
                    .build());
        } catch (RestClientException e) {
            log.error("Error calling On-Chain Indexer API: {}", e.getMessage());
            return Either.left(Problem.builder()
                    .withStatus(Status.SERVICE_UNAVAILABLE)
                    .withTitle(INDEXER_API_ERROR)
                    .withDetail("Error calling indexer API: " + e.getMessage())
                    .build());
        }
    }

    public Either<Problem, Void> testConnection() {
        log.info("Testing connection to On-Chain Indexer at: {}", baseUrl);

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path(TRANSACTIONS_BY_DATE_RANGE_PATH)
                    .queryParam("page", 0)
                    .queryParam("size", 1)
                    .toUriString();

            OnChainTransactionSearchRequest requestBody = new OnChainTransactionSearchRequest(
                    "test",
                    ISO_LOCAL_DATE.format(LocalDate.now()),
                    ISO_LOCAL_DATE.format(LocalDate.now())
            );

            String requestJson = objectMapper.writeValueAsString(requestBody);

            ResponseEntity<String> response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .toEntity(String.class);

            if (response.getStatusCode().is2xxSuccessful() || response.getStatusCode().is4xxClientError()) {
                log.info("On-Chain Indexer connection test successful");
                return Either.right(null);
            }

            return Either.left(Problem.builder()
                    .withStatus(Status.valueOf(response.getStatusCode().value()))
                    .withTitle(INDEXER_API_ERROR)
                    .withDetail("Connection test failed: " + response.getBody())
                    .build());

        } catch (Exception e) {
            log.error("Error testing connection to On-Chain Indexer: {}", e.getMessage());
            return Either.left(Problem.builder()
                    .withStatus(Status.SERVICE_UNAVAILABLE)
                    .withTitle(INDEXER_API_ERROR)
                    .withDetail("Connection test failed: " + e.getMessage())
                    .build());
        }
    }
}
