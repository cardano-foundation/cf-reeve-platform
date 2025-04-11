package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.integration;

import java.time.LocalDate;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.integration.config.JsonConfig;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.integration.config.RestConfig;

@SpringBootTest(classes = NetSuiteClient.class)
@Slf4j
@Import({JsonConfig.class, RestConfig.class})
class ExtractionFullTest {
    @Autowired
    NetSuiteClient netSuiteClient;

    @Test
    void callNetsuite() {
        // Call the NetSuite client to test the connection
        Either<Problem, Optional<String>> optionals = netSuiteClient.retrieveLatestNetsuiteTransactionLines(LocalDate.now().minusMonths(2), LocalDate.now());
        if (optionals.isLeft()) {
            Problem problem = optionals.getLeft();
            System.out.println("Error: " + problem);
        } else {
            Optional<String> response = optionals.get();
            System.out.println("Response: " + response);
        }
    }
}
