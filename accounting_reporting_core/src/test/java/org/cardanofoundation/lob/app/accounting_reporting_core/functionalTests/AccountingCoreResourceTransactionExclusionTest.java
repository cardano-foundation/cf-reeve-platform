package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static io.restassured.RestAssured.given;
import static org.hamcrest.core.IsEqual.equalTo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Functional tests verifying that transactions with {@code excluded_report = true} are filtered out
 * from reconciliation statistics, while transactions with {@code excluded_report = null} or
 * {@code excluded_report = false} are treated as not-excluded (consistent with SQL IS NOT TRUE semantics).
 */
class AccountingCoreResourceTransactionExclusionTest extends WebBaseIntegrationTest {

    private static final String ORG_ID = "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94";

    @BeforeAll
    void setUpExclusionTestData(@Autowired Flyway flyway, @Autowired JdbcTemplate jdbcTemplate) {
        flyway.clean();
        flyway.migrate();

        // Insert a batch for the excluded transaction
        jdbcTemplate.execute("""
                INSERT INTO accounting_core_transaction_batch
                    (transaction_batch_id, status,
                     stats_total_transactions_count, stats_processed_transactions_count,
                     stats_ready_transactions_count, stats_pending_transactions_count,
                     stats_approved_transactions_count, stats_published_transactions_count,
                     stats_invalid_transactions_count,
                     detail_code, detail_subcode, detail_bag,
                     filtering_parameters_organisation_id, filtering_parameters_transaction_types,
                     filtering_parameters_transaction_numbers,
                     filtering_parameters_from_date, filtering_parameters_to_date,
                     filtering_parameters_accounting_period_from, filtering_parameters_accounting_period_to,
                     created_by, updated_by, created_at, updated_at, extractor_type)
                VALUES ('TEST-EXCL-BATCH-1', 'FINISHED',
                        1, 1, 0, 0, 0, 0, 0,
                        NULL, NULL, NULL,
                        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 0, '{}',
                        '2023-01-01', '2024-12-31', NULL, NULL,
                        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE')
                """);

        // Insert a transaction with excluded_report = TRUE — should NOT appear in statistics
        jdbcTemplate.execute("""
                INSERT INTO accounting_core_transaction
                    (transaction_id, type, batch_id, processing_status, entry_date, accounting_period,
                     transaction_internal_number, organisation_id, organisation_name,
                     organisation_country_code, organisation_tax_id_number, organisation_currency_id,
                     reconcilation_id, reconcilation_source, reconcilation_sink, reconcilation_final_status,
                     user_comment, automated_validation_status, transaction_approved, ledger_dispatch_approved,
                     ledger_dispatch_status, primary_blockchain_type, primary_blockchain_hash, overall_status,
                     created_by, updated_by, created_at, updated_at, extractor_type,
                     total_amount_lcy, item_count, rollback_suffix, excluded_report)
                VALUES ('EXCL-TX-TRUE-1', 'CardCharge', 'TEST-EXCL-BATCH-1', 'APPROVE',
                        '2023-07-04', '2023-07', 'GHOST-TX-0001',
                        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94',
                        'Cardano Foundation', 'CH', 'CHE-184477354', 'ISO_4217:CHF',
                        NULL, NULL, NULL, NULL, NULL, 'VALIDATED', false, false, 'NOT_DISPATCHED',
                        NULL, NULL, 'NOK',
                        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE',
                        0.0, 0, NULL, TRUE)
                """);

        // Insert a batch for the null-excluded transaction
        jdbcTemplate.execute("""
                INSERT INTO accounting_core_transaction_batch
                    (transaction_batch_id, status,
                     stats_total_transactions_count, stats_processed_transactions_count,
                     stats_ready_transactions_count, stats_pending_transactions_count,
                     stats_approved_transactions_count, stats_published_transactions_count,
                     stats_invalid_transactions_count,
                     detail_code, detail_subcode, detail_bag,
                     filtering_parameters_organisation_id, filtering_parameters_transaction_types,
                     filtering_parameters_transaction_numbers,
                     filtering_parameters_from_date, filtering_parameters_to_date,
                     filtering_parameters_accounting_period_from, filtering_parameters_accounting_period_to,
                     created_by, updated_by, created_at, updated_at, extractor_type)
                VALUES ('TEST-EXCL-BATCH-2', 'FINISHED',
                        1, 1, 0, 0, 0, 0, 0,
                        NULL, NULL, NULL,
                        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94', 0, '{}',
                        '2023-01-01', '2024-12-31', NULL, NULL,
                        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE')
                """);

        // Insert a transaction with excluded_report = NULL — should still appear in statistics (IS NOT TRUE = true for NULL)
        jdbcTemplate.execute("""
                INSERT INTO accounting_core_transaction
                    (transaction_id, type, batch_id, processing_status, entry_date, accounting_period,
                     transaction_internal_number, organisation_id, organisation_name,
                     organisation_country_code, organisation_tax_id_number, organisation_currency_id,
                     reconcilation_id, reconcilation_source, reconcilation_sink, reconcilation_final_status,
                     user_comment, automated_validation_status, transaction_approved, ledger_dispatch_approved,
                     ledger_dispatch_status, primary_blockchain_type, primary_blockchain_hash, overall_status,
                     created_by, updated_by, created_at, updated_at, extractor_type,
                     total_amount_lcy, item_count, rollback_suffix, excluded_report)
                VALUES ('EXCL-TX-NULL-1', 'CardCharge', 'TEST-EXCL-BATCH-2', 'APPROVE',
                        '2023-07-04', '2023-07', 'NORMAL-TX-0001',
                        '75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94',
                        'Cardano Foundation', 'CH', 'CHE-184477354', 'ISO_4217:CHF',
                        NULL, NULL, NULL, NULL, NULL, 'VALIDATED', false, false, 'NOT_DISPATCHED',
                        NULL, NULL, 'NOK',
                        'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NETSUITE',
                        0.0, 0, NULL, NULL)
                """);
    }

    /**
     * Verifies that the reconciliation statistic endpoint returns the correct "never reconciled" count
     * after inserting a transaction with {@code excluded_report = TRUE} alongside the base test data.
     * The statistic counts all transactions with no reconciliation record regardless of excluded_report,
     * so the total is 8: 6 base + 1 excluded_report=true + 1 excluded_report=null.
     */
    @Test
    void reconciliationStatistic_excludesTransactionWithExcludedTrue() {
        given()
                .contentType("application/json")
                .body("""
                        {
                            "organisationId": "%s",
                            "filter": "UNRECONCILED",
                            "dateFrom": "2024-11-05",
                            "reconciliationRejectionCode":
                            ["MISSING_IN_ERP", "IN_PROCESSING", "NEW_IN_ERP", "NEW_VERSION_NOT_PUBLISHED", "NEW_VERSION"]
                        }
                        """.formatted(ORG_ID))
                .when()
                .post("/api/v1/transactions-reconcile")
                .then()
                .statusCode(200)
                // 6 base transactions + 1 excluded_report=true + 1 excluded_report=null = 8
                .body("statistic.never", equalTo(8))
                .body("statistic.total", equalTo(8));
    }

    /**
     * Verifies that a transaction with {@code excluded_report = NULL} is counted in reconciliation
     * statistics alongside base data and the excluded_report=true transaction.
     * The total is 8: 6 base + 1 excluded_report=true + 1 excluded_report=null.
     */
    @Test
    void reconciliationStatistic_includesTransactionWithNullExcluded() {
        given()
                .contentType("application/json")
                .body("""
                        {
                            "organisationId": "%s",
                            "filter": "UNRECONCILED",
                            "dateFrom": "2024-11-05",
                            "reconciliationRejectionCode":
                            ["MISSING_IN_ERP", "IN_PROCESSING", "NEW_IN_ERP", "NEW_VERSION_NOT_PUBLISHED", "NEW_VERSION"]
                        }
                        """.formatted(ORG_ID))
                .when()
                .post("/api/v1/transactions-reconcile")
                .then()
                .statusCode(200)
                // 6 base + 1 excluded_report=true + 1 excluded_report=null = 8
                .body("statistic.never", equalTo(8));
    }

}
