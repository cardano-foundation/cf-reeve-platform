package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.IsEqual.equalTo;

import org.springframework.beans.factory.annotation.Autowired;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountingCoreResourceReconciliationTest extends WebBaseIntegrationTest{

    @BeforeAll
    void clearDatabase(@Autowired Flyway flyway){
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void testReconciliationResult() {

        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "\"filter\": \"UNRECONCILED\",\n" +
                        "\"dateFrom\": \"2024-11-05\"," +
                        "  \"reconciliationRejectionCode\": " +
                        "[\n\"MISSING_IN_ERP\",  \"IN_PROCESSING\",  \"NEW_IN_ERP\",   \"NEW_VERSION_NOT_PUBLISHED\", \"NEW_VERSION\"\n]" +
                        "}")
                .when()
                .post("/api/v1/transactions-reconcile")
                .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("statistic.missingInERP", equalTo(0))
                .body("statistic.inProcessing", equalTo(0))
                .body("statistic.newInERP", equalTo(0))
                .body("statistic.newVersionNotPublished", equalTo(0))
                .body("statistic.newVersion", equalTo(0))
                .body("statistic.ok", equalTo(0))
                .body("statistic.nok", equalTo(0))
                .body("statistic.never", equalTo(6))
                .body("statistic.total", equalTo(0))
        ;
    }

    @Test
    void testReconciliationTriggerResult() {

        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "\"dateFrom\": \"2014-11-05\",\n" +
                        "\"dateTo\": \"2024-11-01\"" +
                        "}")
                .when()
                .post("/api/v1/reconcile/trigger")
                .then()
                .statusCode(200)
                .body("message", equalTo("We have received your reconcile request now."))
                .body("event", equalTo("RECONCILIATION"))
                .body("success", equalTo(true))
                .body("dateFrom", equalTo("2014-11-05"))
                .body("dateTo", equalTo("2024-11-01"))
                .body("error", equalTo(null))
        ;

    }

    @Test
    void testReconciliationTriggerErrorDateMismatch() {

        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "\"dateFrom\": \"2001-11-05\",\n" +
                        "\"dateTo\": \"2024-11-05\"" +
                        "}")
                .when()
                .post("/api/v1/reconcile/trigger")
                .then()
                .statusCode(400)
                .body("message", equalTo("ORGANISATION_DATE_MISMATCH"))
                .body("event", equalTo("RECONCILIATION"))
                .body("success", equalTo(false))
                .body("error.title", equalTo("ORGANISATION_DATE_MISMATCH"))
        ;

    }

    @Test
    void testReconciliationTriggerErrorOrgNotFound() {

        given()
                .contentType("application/json")
                .body("{\n" +
                        "  \"organisationId\": \"75f95560c1d883ee7228993da5adf725a5d97a13929fd4f477be0faf5020ca94\",\n" +
                        "\"dateFrom\": \"2001-11-05\",\n" +
                        "\"dateTo\": \"2024-11-05\"" +
                        "}")
                .when()
                .post("/api/v1/reconcile/trigger")
                .then()
                .statusCode(400)
                .body("message", equalTo("ORGANISATION_NOT_FOUND"))
                .body("event", equalTo("RECONCILIATION"))
                .body("success", equalTo(false))
                .body("error.title", equalTo("ORGANISATION_NOT_FOUND"))
        ;

    }

    @Test
    void testReconciliationTransactionRejectionCodes() {

        given()
                .contentType("application/json")
                .when()
                .get("/api/v1/transactions-rejection-codes")
                .then()
                .statusCode(200)
                .body(equalTo("[\"MISSING_IN_ERP\",\"IN_PROCESSING\",\"NEW_IN_ERP\",\"NEW_VERSION_NOT_PUBLISHED\",\"NEW_VERSION\"]"))
        ;
    }

}
