package org.cardanofoundation.lob.app.accounting_reporting_core.config;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.cardanofoundation.lob.app.accounting_reporting_core.repository.CoreCurrencyRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items.*;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class BusinessRulesConfig {

    private final Validator validator;
    private final OrganisationPublicApi organisationPublicApi;
    private final CoreCurrencyRepository currencyRepository;

    @Value("${lob.accounting_reporting_core.rules.sanity_check_fields:true}")
    private boolean sanityCheckFields;

    @Value("${lob.accounting_reporting_core.rules.transaction_type_unknown:true}")
    private boolean transactionTypeUnknown;

    @Value("${lob.accounting_reporting_core.rules.discard_zero_balance_tx_items:true}")
    private boolean discardZeroBalanceTxItems;

    @Value("${lob.accounting_reporting_core.rules.amounts_fcy_check:true}")
    private boolean amountsFcyCheck;

    @Value("${lob.accounting_reporting_core.rules.amounts_lcy_check:true}")
    private boolean amountsLcyCheck;

    @Value("${lob.accounting_reporting_core.rules.amount_lcy_balance_zeros_out_check:true}")
    private boolean amountLcyBalanceZerosOutCheck;

    @Value("${lob.accounting_reporting_core.rules.amount_fcy_balance_zeros_out_check:true}")
    private boolean amountFcyBalanceZerosOutCheck;

    @Value("${lob.accounting_reporting_core.rules.journal_account_credit_enrichment:true}")
    private boolean journalAccountCreditEnrichment;

    @Value("${lob.accounting_reporting_core.rules.organisation_conversion:true}")
    private boolean organisationConversion;

    @Value("${lob.accounting_reporting_core.rules.document_conversion:true}")
    private boolean documentConversion;

    @Value("${lob.accounting_reporting_core.rules.cost_center_conversion:true}")
    private boolean costCenterConversion;

    @Value("${lob.accounting_reporting_core.rules.project_conversion:true}")
    private boolean projectConversion;

    @Value("${lob.accounting_reporting_core.rules.account_event_codes_conversion:true}")
    private boolean accountEventCodesConversion;

    @Value("${lob.accounting_reporting_core.rules.discard_same_account_code:true}")
    private boolean discardSameAccountCode;

    @Value("${lob.accounting_reporting_core.rules.tx_items_amounts_summing:true}")
    private boolean txItemsAmountsSumming;

    @Value("${lob.accounting_reporting_core.rules.amounts_lcy_after_summing_check:true}")
    private boolean amountsLcyAfterSummingCheck;

    @Value("${lob.accounting_reporting_core.rules.account_code_debit_check:true}")
    private boolean accountCodeDebitCheck;

    @Value("${lob.accounting_reporting_core.rules.account_code_credit_check:true}")
    private boolean accountCodeCreditCheck;

    @Value("${lob.accounting_reporting_core.rules.document_must_be_present:true}")
    private boolean documentMustBePresent;

    @Value("${lob.accounting_reporting_core.rules.check_if_all_tx_items_are_erased:true}")
    private boolean checkIfAllTxItemsAreErased;

    @Value("${lob.accounting_reporting_core.rules.net_off_credit_debit:true}")
    private boolean netOffCreditDebit;

    @Bean
    @Qualifier("selectorBusinessRulesProcessors")
    public BusinessRulesPipelineProcessor selectorBusinessRulesProcessors() {
        return new BusinessRulesPipelineSelector(
                defaultBusinessRulesProcessor(),
                reprocessBusinessRulesProcessor()
        );
    }

    @Bean
    @Qualifier("defaultBusinessRulesProcessor")
    public BusinessRulesPipelineProcessor defaultBusinessRulesProcessor() {
        val pipelineTasks = new ArrayList<PipelineTask>();
        log.info("Business rules processor initialized - Active rules:\n" +
                "sanityCheckFields={},\n transactionTypeUnknown={},\n discardZeroBalanceTxItems={},\n amountsFcyCheck={},\n amountsLcyCheck={},\n amountLcyBalanceZerosOutCheck={},\n" +
                "amountFcyBalanceZerosOutCheck={},\n journalAccountCreditEnrichment={},\n organisationConversion={},\n documentConversion={},\n costCenterConversion={},\n projectConversion={},\n" +
                "accountEventCodesConversion={},\n discardSameAccountCode={},\n txItemsAmountsSumming={},\n amountsLcyAfterSummingCheck={},\n accountCodeDebitCheck={},\n accountCodeCreditCheck={},\n documentMustBePresent={},\n checkIfAllTxItemsAreErased={},\n netOffCreditDebit={}",
                sanityCheckFields, transactionTypeUnknown, discardZeroBalanceTxItems, amountsFcyCheck, amountsLcyCheck, amountLcyBalanceZerosOutCheck, amountFcyBalanceZerosOutCheck,
                journalAccountCreditEnrichment, organisationConversion, documentConversion, costCenterConversion, projectConversion, accountEventCodesConversion, discardSameAccountCode, txItemsAmountsSumming, amountsLcyAfterSummingCheck, accountCodeDebitCheck,
                accountCodeCreditCheck, documentMustBePresent, checkIfAllTxItemsAreErased, netOffCreditDebit
        );

        pipelineTasks.add(sanityCheckPipelineTask());
        pipelineTasks.add(preCleansingPipelineTask());
        pipelineTasks.add(preValidationPipelineTask());
        pipelineTasks.add(conversionPipelineTask());
        pipelineTasks.add(postCleansingPipelineTask());
        pipelineTasks.add(postValidationPipelineTask());
        pipelineTasks.add(sanityCheckPipelineTask());

        return new DefaultBusinessRulesPipelineProcessor(pipelineTasks);
    }

    @Bean
    @Qualifier("reprocessBusinessRulesProcessor")
    public BusinessRulesPipelineProcessor reprocessBusinessRulesProcessor() {
        val pipelineTasks = new ArrayList<PipelineTask>();

        pipelineTasks.add(conversionPipelineTask());
        pipelineTasks.add(postCleansingPipelineTask());

        return new DefaultBusinessRulesPipelineProcessor(pipelineTasks);
    }

    private PipelineTask sanityCheckPipelineTask() {
        return new DefaultPipelineTask(List.of(
                new SanityCheckFieldsTaskItem(sanityCheckFields, validator),
                new TransactionTypeUnknownTaskItem(transactionTypeUnknown)
        ));
    }

    private PipelineTask preCleansingPipelineTask() {
        return new DefaultPipelineTask(List.of(
                new DiscardZeroBalanceTxItemsTaskItem(discardZeroBalanceTxItems)
        ));
    }

    private PipelineTask preValidationPipelineTask() {
        return new DefaultPipelineTask(List.of(
                new AmountsFcyCheckTaskItem(amountsFcyCheck),
                new AmountsLcyCheckTaskItem(amountsLcyCheck),
                new AmountLcyBalanceZerosOutCheckTaskItem(amountLcyBalanceZerosOutCheck),
                new AmountFcyBalanceZerosOutCheckTaskItem(amountFcyBalanceZerosOutCheck),
                new JournalAccountCreditEnrichmentTaskItem(journalAccountCreditEnrichment, organisationPublicApi)
        ));
    }

    private PipelineTask conversionPipelineTask() {
        return new DefaultPipelineTask(List.of(
                new OrganisationConversionTaskItem(organisationConversion, organisationPublicApi, currencyRepository),
                new DocumentConversionTaskItem(documentConversion, organisationPublicApi, currencyRepository),
                new CostCenterConversionTaskItem(costCenterConversion, organisationPublicApi),
                new ProjectConversionTaskItem(projectConversion, organisationPublicApi),
                new AccountEventCodesConversionTaskItem(accountEventCodesConversion, organisationPublicApi)
        ));
    }

    private PipelineTask postCleansingPipelineTask() {
        return new DefaultPipelineTask(List.of(
                new DiscardSameAccountCodeTaskItem(discardSameAccountCode),
                new TxItemsAmountsSummingTaskItem(txItemsAmountsSumming),
                new AmountsLcyAfterSummingCheckTaskItem(amountsLcyAfterSummingCheck)
        ));
    }

    private PipelineTask postValidationPipelineTask() {
        return new DefaultPipelineTask(List.of(
                new AccountCodeDebitCheckTaskItem(accountCodeDebitCheck),
                new AccountCodeCreditCheckTaskItem(accountCodeCreditCheck),
                new DocumentMustBePresentTaskItem(documentMustBePresent),
                new CheckIfAllTxItemsAreErasedTaskItem(checkIfAllTxItemsAreErased),
                new NetOffCreditDebitTaskItem(netOffCreditDebit, organisationPublicApi)
        ));
    }

}
