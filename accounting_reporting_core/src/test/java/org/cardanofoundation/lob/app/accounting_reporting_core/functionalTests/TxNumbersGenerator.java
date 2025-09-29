package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

public class TxNumbersGenerator {

    private static final String[] PREFIXES = {
            "CARDCH", "CARDHY", "CARDCHRG", "VENDBIL", "TXNBANK", "PAYOFF", "BILLTX"
    };

    public static Set<String> generateUniqueTransactionNumbers(int count) {
        Set<String> transactionNumbers = new LinkedHashSet<>();
        Random random = new Random();

        int uniqueNumber = 100; // Starting unique number

        while (transactionNumbers.size() < count) {
            String prefix = PREFIXES[random.nextInt(PREFIXES.length)];
            String transactionNumber = prefix + uniqueNumber;
            transactionNumbers.add(transactionNumber);
            uniqueNumber++; // Ensure uniqueness by incrementing the number
        }

        return transactionNumbers;
    }

    public static void main(String[] args) {
        int count = 100;
        Set<String> transactionNumbers = TxNumbersGenerator.generateUniqueTransactionNumbers(count);

        System.out.println(transactionNumbers);
    }

}
