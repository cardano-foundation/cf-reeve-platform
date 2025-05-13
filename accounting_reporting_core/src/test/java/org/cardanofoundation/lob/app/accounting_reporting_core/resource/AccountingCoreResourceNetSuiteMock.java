package org.cardanofoundation.lob.app.accounting_reporting_core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.nio.file.Path;

import org.springframework.http.ResponseEntity;

import org.flywaydb.core.internal.util.FileUtils;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class AccountingCoreResourceNetSuiteMockTest {

    private AccountingCoreResourceNetSuiteMock controller;

    private final String mockResultPath = "src/main/resources/json/NetSuiteIngestionMock.json";

    @BeforeEach
    void setUp() throws Exception {
        controller = new AccountingCoreResourceNetSuiteMock();

        // Inject private field `mockResultPath` using reflection
        Field field = AccountingCoreResourceNetSuiteMock.class.getDeclaredField("mockResultPath");
        field.setAccessible(true);
        field.set(controller, mockResultPath);
    }

    @Test
    void testMockNet_ReturnsExpectedJson() {
        String expectedJson = "{\"test\": \"value\"}";

        try (MockedStatic<FileUtils> fileUtilsMock = mockStatic(FileUtils.class)) {
            fileUtilsMock.when(() -> FileUtils.readAsString(Path.of(mockResultPath)))
                    .thenReturn(expectedJson);

            ResponseEntity<String> response = controller.mockNet();

            assertEquals(200, response.getStatusCodeValue());
            assertEquals(expectedJson, response.getBody());
        }
    }
}
