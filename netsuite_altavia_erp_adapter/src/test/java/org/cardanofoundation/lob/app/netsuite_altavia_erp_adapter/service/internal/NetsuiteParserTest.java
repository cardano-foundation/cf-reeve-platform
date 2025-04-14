package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteIngestionEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetsuiteIngestionBody;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionBodyRepository;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreCompress;

@ExtendWith(MockitoExtension.class)
class NetsuiteParserTest {

    private NetSuiteParser netsuiteParser;
    @Mock
    private IngestionBodyRepository ingestionBodyRepository;

    @BeforeEach
    void init() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.findAndRegisterModules();
        netsuiteParser = new NetSuiteParser(objectMapper, ingestionBodyRepository);
    }

    String json = """
                {
                  "more": true,
                  "lines": [
                    {
                      "subsidiarynohierarchy": 1,
                      "type": "VendBill",
                      "datecreated": "2023-10-06 06:53:00",
                      "lastmodifieddate": "2023-10-06 06:54:00",
                      "trandate": "2023-10-06 00:00:00",
                      "enddate": "",
                      "transactionnumber": "DUMMYTRANSACTION",
                      "line": 0,
                      "tranid": "53669",
                      "vendor.entityid": "141",
                      "vendor.companyname": "bossinfo.com AG",
                      "departmentnohierarchy": "",
                      "classnohierarchy": "",
                      "taxcode": "",
                      "account.number": "000000",
                      "account.name": "Dummy Account",
                      "accountmain": "00000 Dummy Account",
                      "currency": "Swiss Franc",
                      "Currency.symbol": "CHF",
                      "exchangerate": "1",
                      "debitfxamount": "",
                      "creditfxamount": "10.00",
                      "debitamount": "",
                      "creditamount": "10.00",
                      "multisubsidiary": "F",
                      "statusref": "open",
                      "account.internalid": "111"
                    }
                  ],
                  "valuehelp": {
                    "": {}
                  }
                }
                """;

    @Test
    void parseSearchResult() {

        Either<Problem, List<TxLine>> parsedSearchResults = netsuiteParser.parseSearchResults(json);

        Assertions.assertTrue(parsedSearchResults.isRight());
        List<TxLine> txLines = parsedSearchResults.get();
        Assertions.assertEquals(1, txLines.size());
        Assertions.assertEquals("VendBill", txLines.get(0).type());
        Assertions.assertEquals("141", txLines.get(0).counterPartyId());
        Assertions.assertEquals("bossinfo.com AG", txLines.get(0).counterPartyName());
        Assertions.assertEquals("DUMMYTRANSACTION", txLines.get(0).transactionNumber());
        Assertions.assertEquals("53669", txLines.get(0).documentNumber());
        Assertions.assertEquals("000000", txLines.get(0).number());
        Assertions.assertEquals("Dummy Account", txLines.get(0).name());
        Assertions.assertEquals("00000 Dummy Account", txLines.get(0).accountMain());
        Assertions.assertEquals("Swiss Franc", txLines.get(0).currency());
        Assertions.assertEquals("CHF", txLines.get(0).currencySymbol());
        Assertions.assertEquals(BigDecimal.ONE, txLines.get(0).exchangeRate());
        Assertions.assertEquals("VendBill", txLines.get(0).type());
        Assertions.assertEquals("141", txLines.get(0).counterPartyId());
    }

    @Test
    void parseSearchResult_wrongResult() {
        String wrongJson = """
                {
                  "more": true
                """;

        Either<Problem, List<TxLine>> parsedSearchResults = netsuiteParser.parseSearchResults(wrongJson);

        Assertions.assertTrue(parsedSearchResults.isLeft());
        Problem problem = parsedSearchResults.getLeft();
        Assertions.assertEquals("JSON_PARSE_ERROR", problem.getTitle());
    }

    @Test
    void getAllTxLinesFromBodies_successfull() {
        NetsuiteIngestionBody body1 = new NetsuiteIngestionBody("1", MoreCompress.compress(json), "1", json, "123");
        NetsuiteIngestionBody body2 = new NetsuiteIngestionBody("2", MoreCompress.compress(json), "1", json, "123");

        Either<Problem, List<TxLine>> allTxLinesFromBodies = netsuiteParser.getAllTxLinesFromBodies(List.of(body1, body2));
        assertTrue(allTxLinesFromBodies.isRight());
        List<TxLine> txLines = allTxLinesFromBodies.get();
        assertEquals(2, txLines.size());
    }

    @Test
    void getAllTxLinesFromBodies_parseErrorInOneItem() {
        String wrongJson = """
                {
                  "more": true
                """;
        NetsuiteIngestionBody body1 = new NetsuiteIngestionBody("1", MoreCompress.compress(json), "1", json, "123");
        NetsuiteIngestionBody body2 = new NetsuiteIngestionBody("2", MoreCompress.compress(wrongJson), "1", json, "123");

        Either<Problem, List<TxLine>> allTxLinesFromBodies = netsuiteParser.getAllTxLinesFromBodies(List.of(body1, body2));
        Assertions.assertTrue(allTxLinesFromBodies.isLeft());
    }

    @Test
    void addLinesToNetsuiteIngestion_emptyBody() {
        NetSuiteIngestionEntity netsuiteIngestionEntity = new NetSuiteIngestionEntity();
        netsuiteParser.addLinesToNetsuiteIngestion(Optional.empty(), "batchId", true);

        Assertions.assertTrue(netsuiteIngestionEntity.getIngestionBodies().isEmpty());
    }

    @Test
    void addLinesToNetsuiteIngestion_emptyTxLines() {
        NetSuiteIngestionEntity netsuiteIngestionEntity = new NetSuiteIngestionEntity();
        netsuiteParser.addLinesToNetsuiteIngestion(Optional.of(List.of("", "")), "batchId", true);

        Assertions.assertTrue(netsuiteIngestionEntity.getIngestionBodies().isEmpty());
    }

}
