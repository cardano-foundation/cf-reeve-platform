package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OnChainTransactionItemDto;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.OnChainTransactionSearchRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.response.OnChainTransactionsPageResponse;

@ExtendWith(MockitoExtension.class)
class OnChainIndexerServiceTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final int PAGE_SIZE = 100;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private OnChainIndexerService service;

    @BeforeEach
    void setUp() {
        service = new OnChainIndexerService(objectMapper, restClient, BASE_URL, PAGE_SIZE);
    }

    @Test
    void retrieveTransactionsByDateRange_shouldReturnTransactions_whenApiCallSucceeds() throws JsonProcessingException {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        OnChainTransactionItemDto item = new OnChainTransactionItemDto(
                "item-1", BigDecimal.valueOf(1000), "1.0", "doc-001", "ISO_4217:CHF",
                "Internal", "9000", "0", "VAT0", "7820T000", "Test Event", "PRJ001", "Test Project"
        );
        OnChainTransactionDto transaction = new OnChainTransactionDto(
                "tx-1", "hash-1", "INT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of(item)
        );
        OnChainTransactionsPageResponse pageResponse = new OnChainTransactionsPageResponse(
                List.of(transaction), 1L, 1, true, 100, 0, true, false
        );

        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{\"organisationId\":\"test-org\",\"dateFrom\":\"2024-01-01\",\"dateTo\":\"2024-01-31\"}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity.ok("{\"content\":[]}");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        when(objectMapper.readValue(anyString(), any(Class.class))).thenReturn(pageResponse);

        // When
        Either<Problem, List<OnChainTransactionDto>> result =
                service.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);

        // Then
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).id()).isEqualTo("tx-1");
    }

    @Test
    void retrieveTransactionsByDateRange_shouldHandlePagination() throws JsonProcessingException {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        OnChainTransactionDto tx1 = new OnChainTransactionDto(
                "tx-1", "hash-1", "INT-001", "2024-01", "batch-1",
                "VendorPayment", "2024-01-15", organisationId, List.of()
        );
        OnChainTransactionDto tx2 = new OnChainTransactionDto(
                "tx-2", "hash-2", "INT-002", "2024-01", "batch-1",
                "VendorPayment", "2024-01-16", organisationId, List.of()
        );

        OnChainTransactionsPageResponse page1 = new OnChainTransactionsPageResponse(
                List.of(tx1), 2L, 2, false, 1, 0, true, false // not last page
        );
        OnChainTransactionsPageResponse page2 = new OnChainTransactionsPageResponse(
                List.of(tx2), 2L, 2, true, 1, 1, false, false // last page
        );

        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity.ok("{}");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        when(objectMapper.readValue(anyString(), any(Class.class)))
                .thenReturn(page1)
                .thenReturn(page2);

        // When
        Either<Problem, List<OnChainTransactionDto>> result =
                service.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);

        // Then
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0).id()).isEqualTo("tx-1");
        assertThat(result.get().get(1).id()).isEqualTo("tx-2");
    }

    @Test
    void retrieveTransactionsByDateRange_shouldReturnProblem_whenRestClientThrowsException() throws JsonProcessingException {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RestClientException("Connection refused"));

        // When
        Either<Problem, List<OnChainTransactionDto>> result =
                service.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
        assertThat(result.getLeft().getStatus()).isEqualTo(Status.SERVICE_UNAVAILABLE);
        assertThat(result.getLeft().getDetail()).contains("Connection refused");
    }

    @Test
    void retrieveTransactionsByDateRange_shouldReturnProblem_whenJsonParsingFails() throws JsonProcessingException {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity.ok("invalid json");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        when(objectMapper.readValue(anyString(), any(Class.class)))
                .thenThrow(new JsonProcessingException("Invalid JSON") {});

        // When
        Either<Problem, List<OnChainTransactionDto>> result =
                service.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
        assertThat(result.getLeft().getStatus()).isEqualTo(Status.INTERNAL_SERVER_ERROR);
        assertThat(result.getLeft().getDetail()).contains("Error parsing JSON");
    }

    @Test
    void retrieveTransactionsByDateRange_shouldReturnProblem_whenApiReturnsErrorStatus() throws JsonProcessingException {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity
                .status(HttpStatusCode.valueOf(500))
                .body("Internal Server Error");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        // When
        Either<Problem, List<OnChainTransactionDto>> result =
                service.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
    }

    @Test
    void retrieveTransactionsByDateRange_shouldReturnEmptyList_whenNoTransactionsFound() throws JsonProcessingException {
        // Given
        String organisationId = "test-org";
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2024, 1, 31);

        OnChainTransactionsPageResponse emptyResponse = new OnChainTransactionsPageResponse(
                List.of(), 0L, 0, true, 100, 0, true, true
        );

        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity.ok("{}");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        when(objectMapper.readValue(anyString(), any(Class.class))).thenReturn(emptyResponse);

        // When
        Either<Problem, List<OnChainTransactionDto>> result =
                service.retrieveTransactionsByDateRange(organisationId, dateFrom, dateTo);

        // Then
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void testConnection_shouldReturnSuccess_whenConnectionIsSuccessful() throws JsonProcessingException {
        // Given
        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity.ok("{}");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        // When
        Either<Problem, Void> result = service.testConnection();

        // Then
        assertThat(result.isRight()).isTrue();
    }

    @Test
    void testConnection_shouldReturnProblem_whenConnectionFails() throws JsonProcessingException {
        // Given
        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenThrow(new RestClientException("Connection refused"));

        // When
        Either<Problem, Void> result = service.testConnection();

        // Then
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
        assertThat(result.getLeft().getDetail()).contains("Connection test failed");
    }

    @Test
    void getBaseUrl_shouldReturnConfiguredBaseUrl() {
        // When
        String baseUrl = service.getBaseUrl();

        // Then
        assertThat(baseUrl).isEqualTo(BASE_URL);
    }

    @Test
    void withDefaultPageSize_shouldCreateServiceWithDefaultValue() {
        // Given
        OnChainIndexerService serviceWithDefaultPageSize = OnChainIndexerService.withDefaultPageSize(objectMapper, restClient, BASE_URL);

        // Then
        assertThat(serviceWithDefaultPageSize.getBaseUrl()).isEqualTo(BASE_URL);
    }

    @Test
    void testConnection_shouldReturnSuccess_whenApiReturns4xxError() throws JsonProcessingException {
        // Given - 4xx errors are considered successful for connection test (API is reachable)
        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity
                .status(HttpStatusCode.valueOf(400))
                .body("Bad Request");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        // When
        Either<Problem, Void> result = service.testConnection();

        // Then - 4xx is still considered a successful connection test
        assertThat(result.isRight()).isTrue();
    }

    @Test
    void testConnection_shouldReturnProblem_whenApiReturns5xxError() throws JsonProcessingException {
        // Given
        when(objectMapper.writeValueAsString(any(OnChainTransactionSearchRequest.class)))
                .thenReturn("{}");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ResponseEntity<String> responseEntity = ResponseEntity
                .status(HttpStatusCode.valueOf(500))
                .body("Internal Server Error");
        when(responseSpec.toEntity(String.class)).thenReturn(responseEntity);

        // When
        Either<Problem, Void> result = service.testConnection();

        // Then - 5xx is considered a failed connection test
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("INDEXER_API_ERROR");
        assertThat(result.getLeft().getDetail()).contains("Connection test failed");
    }
}
