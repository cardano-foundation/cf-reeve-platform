package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.IsEqual.equalTo;

import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Autowired;

import io.restassured.http.Header;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Disabled since the report generation requires a rewrite of these tests")
class ReportControllerTest extends WebBaseIntegrationTest {

    @BeforeEach
    void clearDatabase(@Autowired Flyway flyway){
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void testReportList() {

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true));
    }

    @Test
    void testReportCreateIncomeStatement() {

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("report", equalTo(new ArrayList<>()));


        String inputRequestJson = """
                {
                   "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                   "reportType": "INCOME_STATEMENT",
                   "intervalType": "MONTH",
                   "year": 2023,
                   "period": 1,
                   "otherIncome": "1",
                   "buildOfLongTermProvision": "2",
                   "externalServices": "3",
                   "personnelExpenses": "4",
                   "rentExpenses": "5",
                   "generalAndAdministrativeExpenses": "6",
                   "depreciationAndImpairmentLossesOnTangibleAssets": "7",
                   "amortizationOnIntangibleAssets": "8",
                   "financialRevenues": "9",
                   "realisedGainsOnSaleOfCryptocurrencies": "10",
                   "stakingRewardsIncome": "11",
                   "netIncomeOptionsSale": "12",
                   "financialExpenses": "13",
                   "extraordinaryExpenses": "14",
                   "directTaxes": "15"
                   }""";

        ExtractableResponse<Response> success = given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/report-create")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .extract();

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("report[0].reportId", equalTo(success.body().path("report[0].reportId")));
    }

    @Test
    @Disabled("Disabled since the report generation requires a rewrite of these tests")
    void testReportCreateBalanceSheet() {

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("report", equalTo(new ArrayList<>()));


        String inputRequestJson = """
                {
                "organisationID": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                "reportType": "BALANCE_SHEET",
                "intervalType": "MONTH",
                "year": 2023,
                "period": 1,
                "cashAndCashEquivalents": "1",
                "cryptoAssets": "2",
                "otherReceivables": "3",
                "prepaymentsAndOtherShortTermAssets": "4",
                "financialAssets": "5",
                "investments": "6",
                "tangibleAssets": "7",
                "intangibleAssets": "113",

                "tradeAccountsPayables": "1",
                "otherShortTermLiabilities": "2",
                "accrualsAndShortTermProvisions": "3",
                "provisions": "4",
                "capital": "5",
                "resultsCarriedForward": "6",
                "profitForTheYear": "120"
                }""";

        ExtractableResponse<Response> extract = given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/report-create")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("error", equalTo(null))
                .extract();

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("report[0].reportId", equalTo(extract.body().path("report[0].reportId")));

    }

    @Test
    @Disabled("Disabled since the report generation requires a rewrite of these tests")
    void testReportCreateBalanceSheetWrongData() {

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("report", equalTo(new ArrayList<>()));


        String inputRequestJson = """
                {
                "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                "reportType": "BALANCE_SHEET",
                "intervalType": "MONTH",
                "year": 2023,
                "period": 1,
                "cashAndCashEquivalents": "1",
                "cryptoAssets": "1",
                "otherReceivables": "3",
                "prepaymentsAndOtherShortTermAssets": "4",
                "financialAssets": "5",
                "investments": "6",
                "tangibleAssets": "7",
                "intangibleAssets": "113",

                "tradeAccountsPayables": "1",
                "otherShortTermLiabilities": "2",
                "accrualsAndShortTermProvisions": "3",
                "provisions": "4",
                "capital": "5",
                "resultsCarriedForward": "6",
                "profitForTheYear": "120"
                }""";

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputRequestJson)
                .when()
                .post("/api/v1/report-create")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("report[0].publish", equalTo(false))
                .body("report[0].canBePublish", equalTo(false))
                .body("report[0].error.title", equalTo("INVALID_REPORT_DATA"))
                ;

    }

}
