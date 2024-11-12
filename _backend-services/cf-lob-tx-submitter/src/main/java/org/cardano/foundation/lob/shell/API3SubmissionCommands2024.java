package org.cardano.foundation.lob.shell;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import io.vavr.control.Either;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.cardano.foundation.lob.service.blockchain_state.BlockchainDataChainTipService;
import org.cardano.foundation.lob.service.transaction_submit.TransactionSubmissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

import java.math.BigInteger;

@ShellComponent
@Slf4j
@RequiredArgsConstructor
public class API3SubmissionCommands2024 { // reports

    private final BackendService backendService;
    private final TransactionSubmissionService transactionSubmissionService;
    private final BlockchainDataChainTipService blockchainDataChainTipService;
    private final Account organiserAccount;
    private final Network network;

    @Value("${l1.transaction.metadata.label:1447}")
    private int metadataLabel;

    @ShellMethod(key = "01_create-income-statement")
    @Order(1)
    public String submitReportIncomeStatement() throws Exception {
        log.info("Creating API3 Income Statement event on a {}} network...", network);

        val chainTipE = blockchainDataChainTipService.getChainTip();
        if (chainTipE.isLeft()) {
            throw chainTipE.getLeft();
        }

        val chainTip = chainTipE.get();
        val creationSlot = chainTip.getAbsoluteSlot();

        val reportMap = createIncomeStatementMetadataMap(creationSlot);

        log.info("Serialising metadata and submitting transaction...");

        log.info("INCOME STATEMENT: {}", reportMap.toJson());

        val txBytesE = serialiseTransaction(serialiseMetadata(reportMap));
        if (txBytesE.isLeft()) {
            throw txBytesE.getLeft();
        }
        val txBytes = txBytesE.get();
        val txHash = TransactionUtil.getTxHash(txBytes);

        log.info("Transaction hash: {}", txHash);

        val l1SubmissionDataE = transactionSubmissionService.submitTransactionWithConfirmation(txBytes);
        if (l1SubmissionDataE.isLeft()) {
            throw l1SubmissionDataE.getLeft();
        }
        val l1SubmissionData = l1SubmissionDataE.get();
        log.info("Transaction submitted with confirmation. l1Data: {}", l1SubmissionData);

        return "Created API3 Income Statement report.";
    }

    @ShellMethod(key = "02_create-report-balance-sheet")
    @Order(2)
    public String submitBalanceSheet() throws Exception {
        log.info("Creating API3 Balance Sheet report on a {} network...", network);

        val chainTipE = blockchainDataChainTipService.getChainTip();
        if (chainTipE.isLeft()) {
            log.error("Error getting chain tip. Reason: {}", chainTipE.getLeft().getMessage());

            throw chainTipE.getLeft();
        }
        val chainTip = chainTipE.get();
        val creationSlot = chainTip.getAbsoluteSlot();

        val reportMap = createReportBalanceSheetMetadata(creationSlot);

        log.info("Serialising metadata and submitting transaction...");

        log.info("BALANCE SHEET REPORT: {}", reportMap.toJson());

        val txBytesE = serialiseTransaction(serialiseMetadata(reportMap));
        if (txBytesE.isLeft()) {
            throw txBytesE.getLeft();
        }
        val txBytes = txBytesE.get();
        val txHash = TransactionUtil.getTxHash(txBytes);

        log.info("Transaction hash: {}", txHash);

        val l1SubmissionDataE = transactionSubmissionService.submitTransactionWithConfirmation(txBytes);
        if (l1SubmissionDataE.isLeft()) {
            log.error("Error submitting transaction. Reason: {}", l1SubmissionDataE.getLeft().getMessage());

            throw l1SubmissionDataE.getLeft();
        }

        val l1SubmissionData = l1SubmissionDataE.get();
        log.info("Transaction submitted with confirmation. l1Data: {}", l1SubmissionData);

        log.info("Creating API3 Balance Sheet report on a network: {}", network);

        return "Created API3 Balance Sheet report.";
    }

    private MetadataMap createIncomeStatementMetadataMap(long creationSlot) {
        val topLevel = MetadataBuilder.createMap();

        topLevel.put("type", "REPORT");
        topLevel.put("subType", "INCOME_STATEMENT");
        topLevel.put("interval", "YEAR");
        topLevel.put("year", "2023");
        topLevel.put("mode", "USER");

        // Organization section
        val org = createOrg();
        topLevel.put("org", org);

        // Metadata section
        val metadataMap = createMetadataMap(creationSlot);
        topLevel.put("metadata", metadataMap);

        // Data section
        val data = buildIncomeStatementData();

        // Add data section to the top-level map
        topLevel.put("data", data);

        return topLevel;
    }

    private MetadataMap createReportBalanceSheetMetadata(long creationSlot) {
        val topLevel = MetadataBuilder.createMap();

        topLevel.put("type", "REPORT");
        topLevel.put("subType", "BALANCE_SHEET");
        topLevel.put("interval", "YEAR");
        topLevel.put("year", "2023");
        topLevel.put("mode", "USER");

        // Organization section
        val org = createOrg();
        topLevel.put("org", org);

        // Metadata section
        val metadataMap = createMetadataMap(creationSlot);
        topLevel.put("metadata", metadataMap);

        // Data section
        val data = createBalanceSheetData();

        // Add data section to the top-level map
        topLevel.put("data", data);

        return topLevel;
    }

    private static MetadataMap createMetadataMap(long creationSlot) {
        val metadata = MetadataBuilder.createMap();
        metadata.put("format", "1.0");
        metadata.put("creation_slot", BigInteger.valueOf(creationSlot));

        return metadata;
    }

    private static MetadataMap createOrg() {
        val org = MetadataBuilder.createMap();
        org.put("name", "Cardano Foundation");
        org.put("country_code", "CH");
        org.put("tax_id_number", "CHE-184477354");
        org.put("currency_id", "ISO_4217:CHF");
        org.put("id", "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94");

        return org;
    }

    private MetadataMap createBalanceSheetData() {
        val data = MetadataBuilder.createMap();

        // Assets section
        val assets = MetadataBuilder.createMap();

        // Non-current assets
        val nonCurrentAssets = MetadataBuilder.createMap();
        nonCurrentAssets.put("property_plant_equipment", "500000.00");
        nonCurrentAssets.put("intangible_assets", "200000.00");
        nonCurrentAssets.put("investments", "150000.00");
        nonCurrentAssets.put("financial_assets", "100000.00");
        assets.put("non_current_assets", nonCurrentAssets);

        // Current assets
        val currentAssets = MetadataBuilder.createMap();
        currentAssets.put("prepayments_and_other_short_term_assets", "50000.00");
        currentAssets.put("other_receivables", "75000.00");
        currentAssets.put("crypto_assets", "25000.00");
        currentAssets.put("cash_and_cash_equivalents", "300000.00");
        assets.put("current_assets", currentAssets);

        data.put("assets", assets);

        // Liabilities section
        val liabilities = MetadataBuilder.createMap();

        // Non-current liabilities
        val nonCurrentLiabilities = MetadataBuilder.createMap();
        nonCurrentLiabilities.put("provisions", "250000.00");
        liabilities.put("non_current_liabilities", nonCurrentLiabilities);

        // Current liabilities
        val currentLiabilities = MetadataBuilder.createMap();
        currentLiabilities.put("trade_accounts_payables", "50000.00");
        currentLiabilities.put("other_current_liabilities", "75000.00");
        currentLiabilities.put("accruals_and_short_term_provisions", "25000.00");
        liabilities.put("current_liabilities", currentLiabilities);

        data.put("liabilities", liabilities);

        // Capital section
        val capital = MetadataBuilder.createMap();
        capital.put("capital", "300000.00");
        //capital.put("retained_earnings", "200000.00"); // OLD
        capital.put("results_carried_forward", "200000.00");
        capital.put("profit_for_the_year", "100000.00");

        data.put("capital", capital);

        return data;
    }

    public static MetadataMap buildIncomeStatementData() {
        val data = MetadataBuilder.createMap();

        // Revenues section
        val revenues = MetadataBuilder.createMap();
        revenues.put("other_income", "5000000.00");
        revenues.put("build_of_long_term_provision", "1000000.00");
        data.put("revenues", revenues);

        // COGS (Cost of Goods Sold) section
        val cogs = MetadataBuilder.createMap();
        cogs.put("cost_of_providing_services", "15000000.00");
        data.put("cogs", cogs);

        // Operating Expenses section
        val operatingExpenses = MetadataBuilder.createMap();
        operatingExpenses.put("personnel_expenses", "20000000.00");
        operatingExpenses.put("general_and_administrative_expenses", "5000000.00");
        operatingExpenses.put("depreciation_and_impairment_losses_on_tangible_assets", "3000000.00");
        operatingExpenses.put("amortization_on_intangible_assets", "2000000.00");
        operatingExpenses.put("rentExpenses", "1111.00");
        data.put("operating_expenses", operatingExpenses);

        // Financial Income section
        val financialIncome = MetadataBuilder.createMap();
        financialIncome.put("finance_income", "3000000.00");
        financialIncome.put("finance_expenses", "1500000.00");
        financialIncome.put("realised_gains_on_sale_of_cryptocurrencies", "1000000.00");
        financialIncome.put("staking_rewards_income", "2000000.00");
        financialIncome.put("net_income_options_sale", "500000.00");
        data.put("financial_income", financialIncome);

        // Extraordinary Income section
        val extraordinaryIncome = MetadataBuilder.createMap();
        extraordinaryIncome.put("extraordinary_expenses", "1000000.00");
        data.put("extraordinary_income", extraordinaryIncome);

        // Tax Expenses section
        val taxExpenses = MetadataBuilder.createMap();
        taxExpenses.put("income_tax_expense", "2500000.00");
        data.put("tax_expenses", taxExpenses);

        return data;
    }

    protected Metadata serialiseMetadata(MetadataMap childMetadata) {
        val metadata = MetadataBuilder.createMetadata();
        metadata.put(metadataLabel, childMetadata);

        return metadata;
    }

    protected Either<Exception, byte[]> serialiseTransaction(Metadata metadata) {
        val quickTxBuilder = new QuickTxBuilder(backendService);

        val tx = new Tx()
                .payToAddress(organiserAccount.baseAddress(), Amount.ada(1))
                .attachMetadata(metadata)
                .from(organiserAccount.baseAddress());

        try {
            val txBytes = quickTxBuilder.compose(tx)
                    .withSigner(SignerProviders.signerFrom(organiserAccount))
                    .buildAndSign()
                    .serialize();

            return Either.right(txBytes);
        } catch (Exception e) {
            log.error("Error serialising transaction", e);

            return Either.left(e);
        }
    }

}
