package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.util.Comparator;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;
import org.cardanofoundation.lob.app.support.crypto.SHA3;

@Slf4j
public class ERPSourceTransactionVersionCalculator {

    public static String compute(TransactionEntity transactionEntity) {
        val b = new StringBuilder();

        b.append(transactionEntity.getId());
        b.append(transactionEntity.getTransactionInternalNumber());
        b.append(compute(transactionEntity.getOrganisation()));
        b.append(transactionEntity.getTransactionType());
        b.append(transactionEntity.getEntryDate());

        // to be on the safe side lets sort this in hash calculation logic
        val predictablySortedTxItems = transactionEntity.getItems()
                .stream()
                .sorted(Comparator.comparing(TransactionItemEntity::getId))
                .toList();

        for (val item : predictablySortedTxItems) {
            b.append(compute(item));
        }

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(TransactionItemEntity item) {
        val b = new StringBuilder();

        b.append(item.getId());

        item.getAccountCredit().ifPresent(acc -> b.append(compute(acc)));
        item.getAccountDebit().ifPresent(acc -> b.append(compute(acc)));

        b.append(BigDecimals.normalise(item.getFxRate()));

        b.append(BigDecimals.normalise(item.getAmountFcy()));
        b.append(BigDecimals.normalise(item.getAmountLcy()));

        item.getCostCenter().ifPresent(cc -> b.append(compute(cc)));
        item.getProject().ifPresent(p -> b.append(compute(p)));
        item.getDocument().ifPresent(d -> b.append(compute(d)));

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(Document document) {
        val b = new StringBuilder();

        b.append(document.getNum());

        document.getCounterparty().ifPresent(cp -> b.append(compute(cp)));
        document.getVat().ifPresent(v -> b.append(compute(v)));
        b.append(document.getCurrency().getCustomerCode());

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(Vat vat) {
        val b = new StringBuilder();

        b.append(vat.getCustomerCode());

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(Counterparty counterparty) {
        val b = new StringBuilder();

        b.append(counterparty.getCustomerCode());

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(CostCenter costCenter) {
        val b = new StringBuilder();

        b.append(costCenter.getCustomerCode());

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(Project project) {
        val b = new StringBuilder();

        b.append(project.getCustomerCode());

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(Organisation org) {
        val b = new StringBuilder();

        b.append(org.getId());

        return SHA3.digestAsHex(b.toString());
    }

    private static String compute(Account acc) {
        val b = new StringBuilder();

        b.append(acc.getCode());

        return SHA3.digestAsHex(b.toString());
    }

}
