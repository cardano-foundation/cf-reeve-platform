package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.springframework.stereotype.Service;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.*;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

@Service
@RequiredArgsConstructor
public class API1MetadataSerialiser {

    public static final String VERSION = "1.1";

    private final Clock clock;

    public MetadataMap serialiseToMetadataMap(String organisationId,
                                              Set<TransactionEntity> transactions,
                                              long creationSlot) {
        val globalMetadataMap = MetadataBuilder.createMap();
        globalMetadataMap.put("metadata", createMetadataSection(creationSlot));

        val organisationCollapsable = isOrganisationCollapsable(organisationId, transactions);

        if (organisationCollapsable) {
            globalMetadataMap.put("org", serialise(transactions.stream().findFirst().orElseThrow().getOrganisation()));
        }

        val txList = MetadataBuilder.createList();
        transactions.forEach(tx -> txList.add(serialise(tx, organisationCollapsable)));

        globalMetadataMap.put("type", "INDIVIDUAL_TRANSACTIONS");
        globalMetadataMap.put("data", txList);

        return globalMetadataMap;
    }

    private static boolean isOrganisationCollapsable(String organisationId, Set<TransactionEntity> transactions) {
        return transactions.stream()
                .allMatch(tx -> tx.getOrganisation().getId().equals(organisationId));
    }

    private MetadataMap createMetadataSection(long creationSlot) {
        val metadataMap = MetadataBuilder.createMap();

        val now = Instant.now(clock);

        metadataMap.put("creation_slot", BigInteger.valueOf(creationSlot));
        metadataMap.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
        metadataMap.put("version", VERSION);

        return metadataMap;
    }

    private static MetadataMap serialise(TransactionEntity transaction,
                                         boolean isCollapsableOrganisation) {
        val metadataMap = MetadataBuilder.createMap();

        val id = transaction.getId();

        metadataMap.put("id", id);
        metadataMap.put("number", transaction.getInternalNumber());
        metadataMap.put("batch_id", transaction.getBatchId());

        metadataMap.put("type", transaction.getTransactionType().name());

        metadataMap.put("date", transaction.getEntryDate().toString());
        metadataMap.put("accounting_period", transaction.getAccountingPeriod().toString());

        val transactionItemsMetadataList = MetadataBuilder.createList();

        for (val txLine : transaction.getItems()) {
            transactionItemsMetadataList.add(serialise(txLine));
        }

        if (transactionItemsMetadataList.size() > 0) {
            metadataMap.put("items", transactionItemsMetadataList);
        }

        // if organisation is not collapsable, we send the organisation as part of every transaction inside of this physical transaction
        if (!isCollapsableOrganisation) {
            metadataMap.put("org", serialise(transaction.getOrganisation()));
        }

        return metadataMap;
    }

    private static MetadataMap serialise(CostCenter costCenter) {
        val metadataMap = MetadataBuilder.createMap();
        metadataMap.put("cust_code", costCenter.getCustomerCode());
        metadataMap.put("name", costCenter.getName());

        return metadataMap;
    }

    private static MetadataMap serialise(Project project) {
        val metadataMap = MetadataBuilder.createMap();
        metadataMap.put("cust_code", project.getCustomerCode());
        metadataMap.put("name", project.getName());

        return metadataMap;
    }

    private static MetadataMap serialise(Document document) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("number", document.getNum());
        metadataMap.put("currency", serialise(document.getCurrency()));

        document.getVat().ifPresent(vat -> metadataMap.put("vat", serialise(vat)));
        document.getCounterparty().ifPresent(counterparty -> metadataMap.put("counterparty", serialise(counterparty)));

        return metadataMap;
    }

    private static MetadataMap serialise(Counterparty counterparty) {
        val counterpartyMap = MetadataBuilder.createMap();
        counterpartyMap.put("cust_code", counterparty.getCustomerCode());
        counterpartyMap.put("type", counterparty.getType().name());

        return counterpartyMap;
    }

    private static MetadataMap serialise(Currency currency) {
        val metadataMap = MetadataBuilder.createMap();
        metadataMap.put("id", currency.getId());
        metadataMap.put("cust_code", currency.getCustomerCode());

        return metadataMap;
    }

    private static MetadataMap serialise(Vat vat) {
        val vatMetadataMap = MetadataBuilder.createMap();
        vatMetadataMap.put("cust_code", vat.getCustomerCode());
        vatMetadataMap.put("rate", BigDecimals.normaliseEngineeringString(vat.getRate()));

        return vatMetadataMap;
    }

    private static MetadataMap serialise(TransactionItemEntity transactionItemEntity) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("id", transactionItemEntity.getId());
        metadataMap.put("amount", BigDecimals.normalisePlainString(transactionItemEntity.getAmountFcy()));

        transactionItemEntity.getAccountEvent().ifPresent(accountEvent -> metadataMap.put("event", serialise(accountEvent)));
        transactionItemEntity.getProject().ifPresent(project -> metadataMap.put("project", serialise(project)));
        transactionItemEntity.getCostCenter().ifPresent(costCenter -> metadataMap.put("cost_center", serialise(costCenter)));

        metadataMap.put("fx_rate", BigDecimals.normaliseEngineeringString(transactionItemEntity.getFxRate()));
        metadataMap.put("document", serialise(transactionItemEntity.getDocument()));

        return metadataMap;
    }

    private static MetadataMap serialise(AccountEvent accountEvent) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("code", accountEvent.getCode());
        metadataMap.put("name", accountEvent.getName());

        return metadataMap;
    }

    private static MetadataMap serialise(Organisation org) {
        val metadataMap = MetadataBuilder.createMap();

        metadataMap.put("id", org.getId());
        metadataMap.put("name", org.getName());
        metadataMap.put("tax_id_number", org.getTaxIdNumber());
        metadataMap.put("currency_id", org.getCurrencyId());
        metadataMap.put("country_code", org.getCountryCode());

        return metadataMap;
    }

}
