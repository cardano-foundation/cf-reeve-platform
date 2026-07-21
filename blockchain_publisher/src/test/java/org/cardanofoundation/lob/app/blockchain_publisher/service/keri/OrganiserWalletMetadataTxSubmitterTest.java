package org.cardanofoundation.lob.app.blockchain_publisher.service.keri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.BlockService;
import com.bloxbean.cardano.client.backend.api.MetadataService;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.backend.model.metadata.MetadataJSONContent;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OrganiserWalletMetadataTxSubmitter#submitTransaction(Metadata)} is deliberately not
 * exercised here: it is the same organiser-wallet QuickTx idiom already covered by
 * {@code DocumentL1TransactionCreator#serialiseTransaction} (build + sign) and by the
 * {@code docs/keri} reference scripts (build + sign + submit), so its correctness rests on parity
 * with those, with end-to-end submission verified in milestone 4. This test stubs that seam and
 * asserts only what {@code OrganiserWalletMetadataTxSubmitter} itself is responsible for: label
 * assembly, tx-hash pass-through, error mapping, confirmations arithmetic, and CIP-170 metadata
 * extraction.
 */
class OrganiserWalletMetadataTxSubmitterTest {

    private BackendService backendService;
    private OrganiserWalletMetadataTxSubmitter submitter;

    @BeforeEach
    void setUp() {
        backendService = mock(BackendService.class);
        Account organiserWallet = new Account();
        ObjectMapper objectMapper = new ObjectMapper();

        submitter = spy(new OrganiserWalletMetadataTxSubmitter(backendService, organiserWallet, objectMapper));
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> successResult(T value) {
        Result<T> result = mock(Result.class);
        when(result.isSuccessful()).thenReturn(true);
        when(result.getValue()).thenReturn(value);

        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> failureResult(String response) {
        Result<T> result = mock(Result.class);
        when(result.isSuccessful()).thenReturn(false);
        when(result.getResponse()).thenReturn(response);

        return result;
    }

    @Test
    void submitMetadataTransaction_assemblesLabelAndMetadata_andReturnsTxHashOnSuccess() {
        MetadataMap metadataMap = MetadataBuilder.createMap().put("k", "v");
        doReturn(successResult("txhash123")).when(submitter).submitTransaction(any(Metadata.class));

        Either<ProblemDetail, String> result = submitter.submitMetadataTransaction(170L, metadataMap);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo("txhash123");

        ArgumentCaptor<Metadata> captor = ArgumentCaptor.forClass(Metadata.class);
        verify(submitter).submitTransaction(captor.capture());

        Object stored = captor.getValue().get(BigInteger.valueOf(170L));
        assertThat(stored).isInstanceOfSatisfying(MetadataMap.class,
                storedMap -> assertThat(storedMap.getMap()).isSameAs(metadataMap.getMap()));
    }

    @Test
    void submitMetadataTransaction_mapsUnsuccessfulResult_toProblemDetail() {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        doReturn(failureResult("network error")).when(submitter).submitTransaction(any(Metadata.class));

        Either<ProblemDetail, String> result = submitter.submitMetadataTransaction(1L, metadataMap);

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(problem.getTitle()).isEqualTo(OrganiserWalletMetadataTxSubmitter.AUTH_BEGIN_SUBMISSION_FAILED);
        assertThat(problem.getDetail()).contains("network error");
    }

    @Test
    void submitMetadataTransaction_mapsThrownException_toProblemDetail() {
        MetadataMap metadataMap = MetadataBuilder.createMap();
        doThrow(new RuntimeException("boom")).when(submitter).submitTransaction(any(Metadata.class));

        Either<ProblemDetail, String> result = submitter.submitMetadataTransaction(1L, metadataMap);

        assertThat(result.isLeft()).isTrue();
        ProblemDetail problem = result.getLeft();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(problem.getTitle()).isEqualTo(OrganiserWalletMetadataTxSubmitter.AUTH_BEGIN_SUBMISSION_FAILED);
        assertThat(problem.getDetail()).contains("boom");
    }

    @Test
    void confirmations_returnsDepth_whenTxAndLatestBlockFound() throws ApiException {
        TransactionService transactionService = mock(TransactionService.class);
        BlockService blockService = mock(BlockService.class);
        when(backendService.getTransactionService()).thenReturn(transactionService);
        when(backendService.getBlockService()).thenReturn(blockService);

        TransactionContent txContent = TransactionContent.builder().blockHeight(1000L).build();
        Block latestBlock = Block.builder().height(1005L).build();

        Result<TransactionContent> txResult = successResult(txContent);
        Result<Block> blockResult = successResult(latestBlock);
        when(transactionService.getTransaction("txhash")).thenReturn(txResult);
        when(blockService.getLatestBlock()).thenReturn(blockResult);

        Optional<Long> confirmations = submitter.confirmations("txhash");

        assertThat(confirmations).contains(6L);
    }

    @Test
    void confirmations_returnsEmpty_whenTxNotFound() throws ApiException {
        TransactionService transactionService = mock(TransactionService.class);
        when(backendService.getTransactionService()).thenReturn(transactionService);

        Result<TransactionContent> txResult = failureResult("not found");
        when(transactionService.getTransaction("missing")).thenReturn(txResult);

        Optional<Long> confirmations = submitter.confirmations("missing");

        assertThat(confirmations).isEmpty();
        verify(backendService, never()).getBlockService();
    }

    @Test
    void confirmations_returnsEmpty_whenLatestBlockFails() throws ApiException {
        TransactionService transactionService = mock(TransactionService.class);
        BlockService blockService = mock(BlockService.class);
        when(backendService.getTransactionService()).thenReturn(transactionService);
        when(backendService.getBlockService()).thenReturn(blockService);

        TransactionContent txContent = TransactionContent.builder().blockHeight(1000L).build();
        Result<TransactionContent> txResult = successResult(txContent);
        Result<Block> blockResult = failureResult("blockfrost down");
        when(transactionService.getTransaction("txhash")).thenReturn(txResult);
        when(blockService.getLatestBlock()).thenReturn(blockResult);

        Optional<Long> confirmations = submitter.confirmations("txhash");

        assertThat(confirmations).isEmpty();
    }

    @Test
    void readCip170Metadata_returnsMap_whenLabelPresent() throws Exception {
        MetadataService metadataService = mock(MetadataService.class);
        when(backendService.getMetadataService()).thenReturn(metadataService);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode cip170Content = objectMapper.readTree("{\"t\":\"AUTH_BEGIN\"}");
        MetadataJSONContent content170 = MetadataJSONContent.builder().label("170").jsonMetadata(cip170Content).build();
        MetadataJSONContent contentOther = MetadataJSONContent.builder().label("1447").jsonMetadata(objectMapper.createObjectNode()).build();

        Result<List<MetadataJSONContent>> metadataResult = successResult(List.of(contentOther, content170));
        when(metadataService.getJSONMetadataByTxnHash("txhash")).thenReturn(metadataResult);

        Optional<Map<String, Object>> result = submitter.readCip170Metadata("txhash");

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("t", "AUTH_BEGIN");
    }

    @Test
    void readCip170Metadata_returnsEmpty_whenLabelAbsent() throws ApiException {
        MetadataService metadataService = mock(MetadataService.class);
        when(backendService.getMetadataService()).thenReturn(metadataService);

        ObjectMapper objectMapper = new ObjectMapper();
        MetadataJSONContent contentOther = MetadataJSONContent.builder().label("1447").jsonMetadata(objectMapper.createObjectNode()).build();
        Result<List<MetadataJSONContent>> metadataResult = successResult(List.of(contentOther));
        when(metadataService.getJSONMetadataByTxnHash("txhash")).thenReturn(metadataResult);

        Optional<Map<String, Object>> result = submitter.readCip170Metadata("txhash");

        assertThat(result).isEmpty();
    }

    @Test
    void readCip170Metadata_returnsEmpty_whenTxMissing() throws ApiException {
        MetadataService metadataService = mock(MetadataService.class);
        when(backendService.getMetadataService()).thenReturn(metadataService);

        Result<List<MetadataJSONContent>> metadataResult = failureResult("tx not found");
        when(metadataService.getJSONMetadataByTxnHash("missing")).thenReturn(metadataResult);

        Optional<Map<String, Object>> result = submitter.readCip170Metadata("missing");

        assertThat(result).isEmpty();
    }

}
