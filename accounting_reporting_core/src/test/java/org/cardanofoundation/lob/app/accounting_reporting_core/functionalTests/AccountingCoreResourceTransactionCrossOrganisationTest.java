package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalToCompressingWhiteSpace;
import static org.hamcrest.core.IsEqual.equalTo;

import org.junit.jupiter.api.Test;

/**
 * A user with approve / publish rights in their own organisation must not be able to approve or publish another
 * organisation's transaction by sending its id (LOB-2541).
 */
class AccountingCoreResourceTransactionCrossOrganisationTest extends WebBaseIntegrationTest {

    private static final String OWNING_ORGANISATION_ID = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94";
    private static final String OTHER_ORGANISATION_ID = "0000000000000000000000000000000000000000000000000000000000000b0b";

    @Test
    void transactionOfAnotherOrganisation_cannotBeApprovedOrPublished() {
        // other test classes share the database, so compare with the state found rather than a fixed one
        String approveTxBefore = statistic("ApproveTx");
        String publishTxBefore = statistic("PublishTx");

        postAsOtherOrganisation("/api/v1/transactions/approve", "ApproveTx");
        postAsOtherOrganisation("/api/v1/transactions/publish", "ApproveTx");
        postAsOtherOrganisation("/api/v1/transactions/approve", "PublishTx");
        postAsOtherOrganisation("/api/v1/transactions/publish", "PublishTx");

        // untouched in the owning organisation
        assertThat(statistic("ApproveTx")).isEqualTo(approveTxBefore);
        assertThat(statistic("PublishTx")).isEqualTo(publishTxBefore);
    }

    private void postAsOtherOrganisation(String path, String transactionId) {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "organisationId": "%s",
                          "transactionIds": [ { "id": "%s" } ]
                        }""".formatted(OTHER_ORGANISATION_ID, transactionId))
                .when()
                .post(path)
                .then()
                .statusCode(200)
                .body("id[0]", equalToCompressingWhiteSpace(transactionId))
                .body("success[0]", equalTo(false))
                .body("error[0].title", equalTo("TX_NOT_FOUND"));
    }

    private String statistic(String transactionId) {
        return given()
                .contentType("application/json")
                .queryParam("organisationId", OWNING_ORGANISATION_ID)
                .when()
                .get("/api/v1/transactions/" + transactionId)
                .then()
                .statusCode(200)
                .extract().path("statistic");
    }

}
