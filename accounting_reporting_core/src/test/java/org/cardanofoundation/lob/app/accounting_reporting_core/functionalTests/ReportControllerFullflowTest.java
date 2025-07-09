package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.IsEqual.equalTo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;

import org.springframework.boot.test.mock.mockito.MockBean;

import io.restassured.http.Header;
import org.mockito.Mockito;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@Disabled("Disabled since the report generation requires a rewrite of these tests")
class ReportControllerFullflowTest extends WebBaseIntegrationTest {
    @MockBean
    private Clock clock;

    private static final String ORG_ID = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94";

    @BeforeEach
    void setup() {
        Instant fixedInstant = Instant.parse("2025-02-06T12:00:00Z");
        ZoneId zoneId = ZoneId.of("UTC");
        Mockito.when(clock.instant()).thenReturn(fixedInstant);
        Mockito.when(clock.getZone()).thenReturn(zoneId);
    }

    @Test
    @Order(1)
    void reportListEmptyReports() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/%s".formatted(ORG_ID))
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("report", equalTo(new ArrayList<>()));
    }

    @Test
    @Order(2)
    void createReport() {
        String inputBalanceSheetCreate = """
                {
                    "organisationId": "%s",
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
                  "intangibleAssets": "8",

                  "tradeAccountsPayables": "1",
                  "otherShortTermLiabilities": "2",
                  "accrualsAndShortTermProvisions": "3",
                  "provisions": "4",
                  "capital": "5",
                  "resultsCarriedForward": "6",
                  "profitForTheYear": "15"
                    }
                """.formatted(ORG_ID);

        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body(inputBalanceSheetCreate)
                .when()
                .post("/api/v1/report-create")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("report[0].error", equalTo(null))
                .body("report[0].reportId", equalTo("8fb79106c39a8e1f227e5cb1931a5ad1898dd5e06b6d0fb5d8ac21941f3bf3dd"))
                .body("report[0].publish", equalTo(false))
                .body("report[0].ver", equalTo(0))
                .body("report[0].canBePublish", equalTo(false))
                ;
    }

    @Test
    @Order(3)
    @Disabled // Disabled because due to the report generation this must be adjusted
    void publishReport() {
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .body("""
                        {
                          "organisationId": "%s",
                          "reportId": "8fb79106c39a8e1f227e5cb1931a5ad1898dd5e06b6d0fb5d8ac21941f3bf3dd"
                        }""".formatted(ORG_ID))

                .when()
                .post("/api/v1/report-publish")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("report[0].error", equalTo(null))
                .body("report[0].reportId", equalTo("8fb79106c39a8e1f227e5cb1931a5ad1898dd5e06b6d0fb5d8ac21941f3bf3dd"))
                .body("report[0].publish", equalTo(true))
                .body("report[0].ver", equalTo(0))
                .body("report[0].canBePublish", equalTo(false))

        ;
        given()
                .contentType("application/json")
                .header(new Header("Accept-Language", "en-US"))
                .when()
                .get("/api/v1/report-list/%s".formatted(ORG_ID))
                .then()
                .statusCode(200)
                //.body("id", containsString(expectedUpdatedAt))
                .body("success", equalTo(true))
                .body("total", equalTo(1))
                .body("report[0].error", equalTo(null))
                .body("report[0].reportId", equalTo("8fb79106c39a8e1f227e5cb1931a5ad1898dd5e06b6d0fb5d8ac21941f3bf3dd"))
                .body("report[0].publish", equalTo(true))
                .body("report[0].canBePublish", equalTo(false))
        ;
    }

//    @Test
//    void reportBothReportsTest() {
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .when()
//                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
//                .then()
//                .statusCode(200)
//                //.body("id", containsString(expectedUpdatedAt))
//                .body("success", equalTo(true))
//                .body("report", equalTo(new ArrayList<>()));
//
//
//        val inputBalanceSheetCreate = """
//                {
//                        "organisationID": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                        "reportType": "BALANCE_SHEET",
//                        "intervalType": "MONTH",
//                        "year": 2023,
//                        "period": 1,
//                      "cashAndCashEquivalents": "1",
//                      "cryptoAssets": "2",
//                      "otherReceivables": "3",
//                      "prepaymentsAndOtherShortTermAssets": "4",
//                      "financialAssets": "5",
//                      "investments": "6",
//                      "propertyPlantEquipment": "7",
//                      "intangibleAssets": "113",
//
//                      "tradeAccountsPayables": "1",
//                      "otherCurrentLiabilities": "2",
//                      "accrualsAndShortTermProvisions": "3",
//                      "provisions": "4",
//                      "capital": "5",
//                      "resultsCarriedForward": "6",
//                      "profitForTheYear": "120"
//                    }""";
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body(inputBalanceSheetCreate)
//                .when()
//                .post("/api/v1/report-create")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"))
//                .body("report[0].publish", equalTo(false))
//                .body("report[0].ver", equalTo(1))
//                .body("report[0].canBePublish", equalTo(true))
//                .body("report[0].error", equalTo(null))
//
//        ;
//
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body("""
//                        {
//                          "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                          "reportId": "8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"
//                        }""")
//                .when()
//                .post("/api/v1/report-publish")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"))
//                .body("report[0].publish", equalTo(true))
//                .body("report[0].ver", equalTo(1))
//                .body("report[0].canBePublish", equalTo(true))
//                .body("report[0].error", equalTo(null))
//        ;
//
////        given()
////                .contentType("application/json")
////                .header(new Header("Accept-Language", "en-US"))
////                .when()
////                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
////                .then()
////                .statusCode(200)
////                //.body("id", containsString(expectedUpdatedAt))
////                .body("success", equalTo(true))
////                .body("report[0].reportId", equalTo("1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"))
////                .body("report[0].publish", equalTo(true))
////                .body("report[0].canBePublish", equalTo(true))
////                .body("report[0].error", equalTo(null))
////                .body("report[1].reportId", equalTo("8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"))
////                .body("report[1].publish", equalTo(false))
////                .body("report[1].canBePublish", equalTo(true))
////                .body("report[1].error", equalTo(null));
//
//
//        val inputIncomeStatementUpdate = """
//                {
//                   "organisationID": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                   "reportType": "INCOME_STATEMENT",
//                   "intervalType": "MONTH",
//                   "year": 2023,
//                   "period": 1,
//                   "otherIncome": "1",
//                   "buildOfLongTermProvision": "2",
//                   "costOfProvidingServices": "3",
//                   "personnelExpenses": "4",
//                   "rentExpenses": "5",
//                   "generalAndAdministrativeExpenses": "6",
//                   "depreciationAndImpairmentLossesOnTangibleAssets": "7",
//                   "amortizationOnIntangibleAssets": "8",
//                   "financialRevenues": "9",
//                   "realisedGainsOnSaleOfCryptocurrencies": "10",
//                   "stakingRewardsIncome": "11",
//                   "netIncomeOptionsSale": "12",
//                   "financialExpenses": "13",
//                   "extraordinaryExpenses": "14",
//                   "incomeTaxExpense": "16"
//                   }""";
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body(inputIncomeStatementUpdate)
//                .when()
//                .post("/api/v1/report-create")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"))
//                .body("report[0].publish", equalTo(false))
//                .body("report[0].canBePublish", equalTo(false))
//                .body("report[0].error.title", equalTo("PROFIT_FOR_THE_YEAR_MISMATCH"))
//        ;
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body("""
//                        {
//                          "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                          "reportId": "1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"
//                        }""")
//                .when()
//                .post("/api/v1/report-publish")
//                .then()
//                .statusCode(400)
//                .body("success", equalTo(false))
//                .body("error.title", equalTo("PROFIT_FOR_THE_YEAR_MISMATCH"))
//        ;
//
//        val inputIncomeStatementCorrection = """
//                {
//                   "organisationID": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                   "reportType": "INCOME_STATEMENT",
//                   "intervalType": "MONTH",
//                   "year": 2023,
//                   "period": 1,
//                   "otherIncome": "1",
//                   "buildOfLongTermProvision": "2",
//                   "costOfProvidingServices": "3",
//                   "personnelExpenses": "4",
//                   "rentExpenses": "5",
//                   "generalAndAdministrativeExpenses": "6",
//                   "depreciationAndImpairmentLossesOnTangibleAssets": "7",
//                   "amortizationOnIntangibleAssets": "8",
//                   "financialRevenues": "9",
//                   "realisedGainsOnSaleOfCryptocurrencies": "10",
//                   "stakingRewardsIncome": "11",
//                   "netIncomeOptionsSale": "12",
//                   "financialExpenses": "13",
//                   "extraordinaryExpenses": "14",
//                   "incomeTaxExpense": "15"
//                   }""";
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body(inputIncomeStatementCorrection)
//                .when()
//                .post("/api/v1/report-create")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"))
//                .body("report[0].publish", equalTo(false))
//        ;
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body("""
//                        {
//                          "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                          "reportId": "8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"
//                        }""")
//                .when()
//                .post("/api/v1/report-publish")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"))
//                .body("report[0].publish", equalTo(true))
//        ;
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .when()
//                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"))
//                .body("report[0].publish", equalTo(true))
//                .body("report[1].reportId", equalTo("1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"))
//                .body("report[1].publish", equalTo(false));
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .body("""
//                        {
//                          "organisationId": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
//                          "reportId": "1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"
//                        }""")
//                .when()
//                .post("/api/v1/report-publish")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"))
//                .body("report[0].publish", equalTo(true));
//
//        given()
//                .contentType("application/json")
//                .header(new Header("Accept-Language", "en-US"))
//                .when()
//                .get("/api/v1/report-list/75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94")
//                .then()
//                .statusCode(200)
//                .body("success", equalTo(true))
//                .body("report[0].reportId", equalTo("8d8209cb555b7c71a5a90ad52ce49f4ea4bd1948489a49cd5eedc3fab958d968"))
//                .body("report[0].publish", equalTo(true))
//                .body("report[1].reportId", equalTo("1e1da8241a6e0349a31f7cbadc057e2c499964025b653f77bb5b5da4f7a9c55d"))
//                .body("report[1].publish", equalTo(true));
//
//    }
}
