package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TransactionDataSearchResult;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;

@Slf4j
@RequiredArgsConstructor
public class NetSuiteParser {

    private final ObjectMapper objectMapper;

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

}
