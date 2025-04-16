package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static java.util.Objects.requireNonNull;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreCompress.decompress;
import static org.cardanofoundation.lob.app.support.crypto.MD5Hashing.md5;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TransactionDataSearchResult;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteIngestionEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetsuiteIngestionBody;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionBodyRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreCompress;

@Slf4j
@RequiredArgsConstructor
public class NetSuiteParser {

    private final ObjectMapper objectMapper;
    private final IngestionRepository ingestionRepository;
    private final IngestionBodyRepository ingestionBodyRepository;
    private final String netsuiteInstanceId;
    private final Clock clock;

    public Either<Problem, List<TxLine>> parseSearchResults(String jsonString) {
        try {
            TransactionDataSearchResult transactionDataSearchResult = objectMapper.readValue(jsonString, TransactionDataSearchResult.class);

            return Either.right(transactionDataSearchResult.lines());
        } catch (JsonProcessingException e) {
            log.error("Error parsing NetSuite search result: {}", e.getMessage(), e);

            return Either.left(Problem.builder()
                    .withTitle("JSON_PARSE_ERROR")
                    .withDetail(STR."JSON rrror parsing NetSuite search error: \{e.getMessage()}")
                    .build());
        }
    }

    public Either<Problem, List<TxLine>> getAllTxLinesFromBodies(List<NetsuiteIngestionBody> ingestionBodies) {
        List<TxLine> txLines = new ArrayList<>();
        for (NetsuiteIngestionBody ingestionBody : ingestionBodies) {
            Either<Problem, List<TxLine>> txLinesE = parseSearchResults(requireNonNull(decompress(ingestionBody.getIngestionBody())));
            if (txLinesE.isLeft()) {
                return txLinesE;
            }
            txLines.addAll(txLinesE.get());
        }
        return Either.right(txLines);
    }

    public void addLinesToNetsuiteIngestion(Optional<List<String>> bodyM, NetSuiteIngestionEntity netSuiteIngestion, String batchId, boolean isNetSuiteInstanceDebugMode, String user) {
        if(bodyM.isEmpty()) {
            return;
        }
        for(String netsuiteTransactionLinesJson : bodyM.get()) {
            if(netsuiteTransactionLinesJson.isEmpty()) {
                continue;
            }
            String ingestionBodyChecksum = md5(netsuiteTransactionLinesJson);
            String compressedBody = MoreCompress.compress(netsuiteTransactionLinesJson);
            log.info("Before compression: {}, compressed: {}", netsuiteTransactionLinesJson.length(), compressedBody.length());

            NetsuiteIngestionBody body = new NetsuiteIngestionBody();
            body.setIngestionBody(compressedBody);
            if (isNetSuiteInstanceDebugMode) {
                body.setIngestionBodyDebug(netsuiteTransactionLinesJson);
            }
            body.setIngestionBodyChecksum(ingestionBodyChecksum);
            body.setNetsuiteIngestionId(batchId);
            body.setCreatedBy(user);
            body.setUpdatedBy(user);
            body.setCreatedAt(LocalDateTime.now(clock));
            body.setUpdatedAt(LocalDateTime.now(clock));
            netSuiteIngestion.addBody(body);
        }

    }

    @Transactional
    NetSuiteIngestionEntity saveToDataBase(String batchId, Optional<List<String>> bodyM, boolean isNetSuiteInstanceDebugMode, String user) {
        NetSuiteIngestionEntity netSuiteIngestion = new NetSuiteIngestionEntity();
        netSuiteIngestion.setId(batchId);
        netSuiteIngestion.setAdapterInstanceId(netsuiteInstanceId);
        netSuiteIngestion.setCreatedBy(user);
        netSuiteIngestion.setUpdatedBy(user);
        netSuiteIngestion.setCreatedAt(LocalDateTime.now(clock));
        netSuiteIngestion.setUpdatedAt(LocalDateTime.now(clock));
        this.addLinesToNetsuiteIngestion(bodyM, netSuiteIngestion, batchId, isNetSuiteInstanceDebugMode, user);
        NetSuiteIngestionEntity storedNetsuiteIngestion = ingestionRepository.saveAndFlush(netSuiteIngestion);

        return storedNetsuiteIngestion;
    }

}
