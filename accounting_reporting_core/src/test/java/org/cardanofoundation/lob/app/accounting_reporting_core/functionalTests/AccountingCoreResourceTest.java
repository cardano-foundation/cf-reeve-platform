package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.IsEqual.equalTo;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import io.restassured.http.Header;
import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountingCoreResourceTest extends WebBaseIntegrationTest{

    @BeforeAll
    void clearDatabase(@Autowired Flyway flyway){
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void testListAllTransactionsNoOrgnanisationId() {
        String inputRequestJson = """
                {
                  "organisationId": "",
                  "transactionType": [
                    "CardCharge",
                    "VendorBill",
                    "CardRefund",
                    "Journal",
                    "FxRevaluation",
                    "Transfer",
                    "CustomerPayment",
                    "ExpenseReport",
                    "VendorPayment",
                    "BillCredit"
                  ],
                    "status": ["VALIDATED","FAILED"]
                }""";

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/transactions")
                .then()
                .statusCode(400)
                .body(equalTo("{\"type\":\"https://zalando.github.io/problem/constraint-violation\",\"status\":400,\"violations\":[{\"field\":\"organisationId\",\"message\":\"Organisation Id is mandatory and must not be blank or null.\"}],\"title\":\"Constraint Violation\"}"));
    }

    @Test
    void testExtractionTrigger() {
        String inputRequestJson = """
                {
                  "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                  "dateFrom": "2023-01-01",
                  "dateTo": "2024-05-01",
                  "transactionType": [
                    "CardCharge",
                    "VendorBill",
                    "CardRefund",
                    "Journal",
                    "FxRevaluation",
                    "Transfer",
                    "CustomerPayment",
                    "ExpenseReport",
                    "VendorPayment",
                    "BillCredit"
                  ],
                  "transactionNumbers": [
                    "CARDCH565",
                    "CARDHY777",
                    "CARDCHRG159",
                    "VENDBIL119"
                  ]
                }""";

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/extraction")
                .then()
                .statusCode(202)
                .body("event", equalTo("EXTRACTION"))
                .body("message", equalTo("We have received your extraction request now. Please review imported transactions from the batch list."));
    }

    @Test
    void testExtractionTriggerFailDueToToManyTransactionNumbers() {
        Set<String> numbers = TxNumbersGenerator.generateUniqueTransactionNumbers(601);
        // turn into json string as array including opening and ending brackets
        String transactionNumbersJson = numbers.stream().collect(Collectors.joining("\",\"", "[\"", "\"]"));

        String inputRequestJson = """
                                    {
                                      "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                                      "dateFrom": "2023-01-01",
                                      "dateTo": "2024-05-01",
                                      "transactionType": [
                                        "CardCharge",
                                        "VendorBill",
                                        "CardRefund",
                                        "Journal",
                                        "FxRevaluation",
                                        "Transfer",
                                        "CustomerPayment",
                                        "ExpenseReport",
                                        "VendorPayment",
                                        "BillCredit"
                                      ],
                                      "transactionNumbers": %s
                }""".formatted(transactionNumbersJson);

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/extraction")
                .then()
                .statusCode(400)
                .body("title", equalTo("TOO_MANY_TRANSACTIONS"))
                .body("detail", equalTo("Too many transactions requested, maximum is 600"));
    }

    @Test
    void testListAllActionWrongDate() {
        String inputRequestJson = """
                {
                  "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                  "dateFrom": "2003-01-01",
                  "dateTo": "2024-05-01",
                  "transactionType": [
                    "CardCharge",
                    "VendorBill",
                    "CardRefund",
                    "Journal",
                    "FxRevaluation",
                    "Transfer",
                    "CustomerPayment",
                    "ExpenseReport",
                    "VendorPayment",
                    "BillCredit"
                  ],
                  "transactionNumbers": [
                    "CARDCH565",
                    "CARDHY777",
                    "CARDCHRG159",
                    "VENDBIL119"
                  ]
                }""";

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/extraction")
                .then()
                .statusCode(400)
                .body("title", equalTo("ORGANISATION_DATE_MISMATCH"))
                .body("detail", startsWith("Date range must be within the accounting period:"));
    }

    @Test
    void testTransactionType() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/transaction-types")
                .then()
                .statusCode(200)
                .body("find { it.title == 'Card Refund' }.id", equalTo("CardRefund"))
                .body("find { it.title == 'Card Charge' }.id", equalTo("CardCharge"))
                .body("find { it.title == 'Vendor Bill' }.id", equalTo("VendorBill"))
                .body("find { it.title == 'Customer Payment' }.id", equalTo("CustomerPayment"))
                .body("find { it.title == 'Transfer' }.id", equalTo("Transfer"))
                .body("find { it.title == 'Vendor Payment' }.id", equalTo("VendorPayment"))
                .body("find { it.title == 'Journal' }.id", equalTo("Journal"))
                .body("find { it.title == 'Fx Revaluation' }.id", equalTo("FxRevaluation"))
                .body("find { it.title == 'Bill Credit' }.id", equalTo("BillCredit"))
                .body("find { it.title == 'Expense Report' }.id", equalTo("ExpenseReport"));
    }

    @Test
    void testRejectionReasons() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/rejection-reasons")
                .then()
                .statusCode(200)
                .body(containsString("INCORRECT_AMOUNT"))
                .body(containsString("INCORRECT_COST_CENTER"))
                .body(containsString("INCORRECT_PROJECT"))
                .body(containsString("INCORRECT_CURRENCY"))
                .body(containsString("INCORRECT_VAT_CODE"))
                .body(containsString("REVIEW_PARENT_COST_CENTER"))
                .body(containsString("REVIEW_PARENT_PROJECT_CODE"));
    }

    @Test
    void testListAllBatch() {
        String inputRequestJson = """
                {
                    "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"
                }
                """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH5-Publish"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("total", equalTo(6));
    }

    @Test
    void testListAllBatchPending() {
        String inputRequestJson = """
    {
        "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
        "batchStatistics": [
            "PENDING"
        ]
    }
    """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH2-TestRejection"))
                .body("batchs.createdAt[0]", containsString("2024-07-17"))
                .body("batchs.updatedAt[0]", containsString("2024-08-16"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("batchs.batchStatistics[0].invalid", equalTo(0))
                .body("batchs.batchStatistics[0].pending", equalTo(1))
                .body("batchs.batchStatistics[0].approve", equalTo(0))
                .body("batchs.batchStatistics[0].publish", equalTo(0))
                .body("batchs.batchStatistics[0].published", equalTo(0))
                .body("batchs.batchStatistics[0].total", equalTo(1))
                .body("total", equalTo(2));
    }

    @Test
    void testListAllBatchInvalid() {
        String inputRequestJson = """
    {
        "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
        "batchStatistics": [
            "INVALID"
        ]
    }
    """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH3-Invalid"))
                .body("batchs.createdAt[0]", containsString("2024-01-15"))
                .body("batchs.updatedAt[0]", containsString("2024-01-15"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("batchs.batchStatistics[0].invalid", equalTo(1))
                .body("batchs.batchStatistics[0].pending", equalTo(0))
                .body("batchs.batchStatistics[0].approve", equalTo(0))
                .body("batchs.batchStatistics[0].publish", equalTo(0))
                .body("batchs.batchStatistics[0].published", equalTo(0))
                .body("batchs.batchStatistics[0].total", equalTo(1))
                .body("total", equalTo(2));

    }

    @Test
    void testListAllBatchApprove() {
        String inputRequestJson = """
    {
        "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
        "batchStatistics": [
            "APPROVE"
        ]
    }
    """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH4-Approve"))
                .body("batchs.createdAt[0]", containsString("2024-08-16"))
                .body("batchs.updatedAt[0]", containsString("2024-08-16"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("batchs.batchStatistics[0].invalid", equalTo(0))
                .body("batchs.batchStatistics[0].pending", equalTo(0))
                .body("batchs.batchStatistics[0].approve", equalTo(3))
                .body("batchs.batchStatistics[0].publish", equalTo(0))
                .body("batchs.batchStatistics[0].published", equalTo(0))
                .body("batchs.batchStatistics[0].total", equalTo(3))
                .body("total", equalTo(1));
    }

    @Test
    void testListAllBatchPublish() {
        String inputRequestJson = """
    {
        "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
        "batchStatistics": [
            "PUBLISH"
        ]
    }
    """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH5-Publish"))
                .body("batchs.createdAt[0]", containsString("2024-08-18"))
                .body("batchs.updatedAt[0]", containsString("2024-08-18"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("batchs.batchStatistics[0].invalid", equalTo(0))
                .body("batchs.batchStatistics[0].pending", equalTo(0))
                .body("batchs.batchStatistics[0].approve", equalTo(0))
                .body("batchs.batchStatistics[0].publish", equalTo(3))
                .body("batchs.batchStatistics[0].published", equalTo(0))
                .body("batchs.batchStatistics[0].total", equalTo(3))
                .body("total", equalTo(2));
    }

    @Test
    void testListAllBatchPublished() {
        String inputRequestJson = """
    {
        "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
        "batchStatistics": [
            "PUBLISHED"
        ]
    }
    """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH6-Published"))
                .body("batchs.createdAt[0]", containsString("2024-07-17"))
                .body("batchs.updatedAt[0]", containsString("2024-07-17"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("batchs.batchStatistics[0].invalid", equalTo(0))
                .body("batchs.batchStatistics[0].pending", equalTo(0))
                .body("batchs.batchStatistics[0].approve", equalTo(0))
                .body("batchs.batchStatistics[0].publish", equalTo(2))
                .body("batchs.batchStatistics[0].published", equalTo(1))
                .body("batchs.batchStatistics[0].total", equalTo(3))
                .body("total", equalTo(1));
    }

    @Test
    void testListAllBatchByTime() {
        String inputRequestJson = """
    {
        "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
        "from": "2024-08-17",
        "to": "2024-08-19"
    }
    """;

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(200)
                .body("batchs.id[0]", containsString("DUMMY_BATCH5-Publish"))
                .body("batchs.createdAt[0]", containsString("2024-08-18"))
                .body("batchs.updatedAt[0]", containsString("2024-08-18"))
                .body("batchs.organisationId[0]", containsString("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .body("batchs.batchStatistics[0].invalid", equalTo(0))
                .body("batchs.batchStatistics[0].pending", equalTo(0))
                .body("batchs.batchStatistics[0].approve", equalTo(0))
                .body("batchs.batchStatistics[0].publish", equalTo(3))
                .body("batchs.batchStatistics[0].published", equalTo(0))
                .body("batchs.batchStatistics[0].total", equalTo(3))
                .body("total", equalTo(1));
    }

    @Test
    void testListAllBatchNoBody() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .post("/api/v1/batches")
                .then()
                .statusCode(400)
                .body("title", equalTo("Bad Request"));
    }

    @Test
    void testListAllBatchDetail() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/batches/DUMMY_BATCH4-Approve")
                .then()
                .statusCode(200)
                .body("id", equalTo("DUMMY_BATCH4-Approve"))
                .body("organisationId", equalTo("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"));
    }

    @Test
    void testListAllBatchDetailNull() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/batches/fb47142027c0788116d14723a4ab4a67636a7d6463d84f0c6f7adf61aba32c04")
                .then()
                .statusCode(404)
                .body("title", equalTo("BATCH_NOT_FOUND"))
                .body("detail", equalTo("Batch with id: {fb47142027c0788116d14723a4ab4a67636a7d6463d84f0c6f7adf61aba32c04} could not be found"));
    }

    @Test
    void testListAllBatchReprocess() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/batches/reprocess/DUMMY_BATCH4-Approve")
                .then()
                .statusCode(200)
                .body("batchId", equalTo("DUMMY_BATCH4-Approve"))
                .body("success", equalTo(true));
    }

    @Test
    void testListAllBatchReprocessNoExist() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/batches/reprocess/fake")
                .then()
                .statusCode(200)
                .body("batchId", equalTo("fake"))
                .body("success", equalTo(false));
    }

}
