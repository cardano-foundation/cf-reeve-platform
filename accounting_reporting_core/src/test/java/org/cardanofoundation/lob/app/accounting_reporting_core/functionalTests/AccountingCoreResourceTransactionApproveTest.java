package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.IsEqual.equalTo;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Test;

@Slf4j
class AccountingCoreResourceTransactionApproveTest extends WebBaseIntegrationTest {

    @Test
    void testApproveTransaction() {
        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/ApproveTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("APPROVE"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                          "transactionIds": [
                            {
                              "id": "ApproveTx"
                            }
                          ]
                        }""")
                .when()
                .post("/api/transactions/approve")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("ApproveTx"))
                .body("success[0]", equalTo(true))
        ;
        given()
                .contentType("application/json")
                .when()
                .get("/api/transactions/ApproveTx")
                .then()
                .statusCode(200)
                .body("statistic", equalTo("PUBLISH"));
    }

    @Test
    void testApproveTransactionTransactionNotFound() {
        given()
                .contentType("application/json")
                .body("""
                        {
                            "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                            "transactionIds": [
                                {
                                  "id": "NotExistingTransaction"
                                }
                            ]
                        }
                        """)
                .when()
                .post("/api/transactions/approve")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("NotExistingTransaction"))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("TX_NOT_FOUND"))
        ;
    }

    @Test
    void testApproveTransactionFailedTransactionViolation() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                          "transactionIds": [
                            {
                              "id": "InvalidTx"
                            }
                          ]
                        }""")
                .when()
                .post("/api/transactions/approve")
                .then()
                .statusCode(200)
                .body("id[0]", equalTo("InvalidTx"))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("CANNOT_APPROVE_FAILED_TX"))
        ;

    }

}
