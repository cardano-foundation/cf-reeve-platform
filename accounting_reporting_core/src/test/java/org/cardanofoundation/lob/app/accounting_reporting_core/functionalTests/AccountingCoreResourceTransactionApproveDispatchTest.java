package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.IsEqual.equalTo;

import org.springframework.beans.factory.annotation.Autowired;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class AccountingCoreResourceTransactionApproveDispatchTest extends WebBaseIntegrationTest {

    @BeforeAll
    void clearDatabase(@Autowired Flyway flyway){
        flyway.clean();
        flyway.migrate();
    }

    @Test
    @Order(1)
    void testApproveDispatchTransaction() {

        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/PublishTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISH"));


        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"PublishTx\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("PublishTx"))
                .body("success[0]", equalTo(true))
        ;
        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/PublishTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISHED"));
    }

    @Test
    @Order(2)
    void testApproveDispatchTransactionAlreadyDispatched() {

        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/PublishTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISHED"));

        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"PublishTx\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("PublishTx"))
                .body("success[0]", equalTo(true))
        ;

        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/PublishTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISHED"));

    }

    @Test
    @Order(3)
    void testApproveDispatchTransactionAlreadyMarkForDispatch() {

        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/PublishTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISHED"));

        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"PublishTx\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("PublishTx"))
                .body("success[0]", equalTo(true))
        ;
        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/PublishTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISHED"));

    }

    @Test
    @Order(4)
    void testApproveDispatchTransactionNotReadyToPublish() {
        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"ApproveTx\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("ApproveTx"))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("TX_NOT_APPROVED"))

        ;
    }

    @Test
    @Order(5)
    void testApproveDispatchTransactionTransactionNotFound() {
        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"ReadyToApprove_1_8a283b41eab57add98278561ab51d23f3f3daa461b84aca\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("ReadyToApprove_1_8a283b41eab57add98278561ab51d23f3f3daa461b84aca"))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("TX_NOT_FOUND"))
        ;
    }

    @Test
    @Order(6)
    void testApproveDispatchTransactionFailedTransactionViolation() {
        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"InvalidTx\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("InvalidTx"))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("CANNOT_APPROVE_FAILED_TX"))
        ;
    }

    @Test
    @Order(7)
    void testApproveDispatchTransactionFailedTransactionItemRejected() {
        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "  \"transactionIds\": [\n" +
                        "    {\n" +
                        "      \"id\": \"InvalidTx\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/transactions/publish")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("InvalidTx"))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("CANNOT_APPROVE_FAILED_TX"))
        ;
    }


}
