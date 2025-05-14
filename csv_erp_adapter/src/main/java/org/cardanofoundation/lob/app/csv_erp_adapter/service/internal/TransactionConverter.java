package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static java.util.stream.Collectors.groupingBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import io.vavr.control.Either;
import org.apache.commons.lang3.StringUtils;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Project;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Vat;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.TransactionLine;
import org.cardanofoundation.lob.app.support.calc.MoreBigDecimal;
import org.cardanofoundation.lob.app.support.date.FlexibleDateParser;

@Service("CsvTransactionConverter")
@Slf4j
@RequiredArgsConstructor
public class TransactionConverter {

    public Either<Problem, List<Transaction>> convertToTransaction(String organisationId, String batchId, List<TransactionLine> lines) {
        Map<String, List<TransactionLine>> collect = lines.stream().collect(groupingBy(TransactionLine::getTxNumber));
        List<Transaction> transactions = new ArrayList<>();
        for (Map.Entry<String, List<TransactionLine>> entry : collect.entrySet()) {
            List<TransactionLine> transactionLines = entry.getValue();
            if (transactionLines.isEmpty()) {
                continue; // skipping
            }
            TransactionType transactionType;
            try {
                transactionType = TransactionType.valueOf(transactionLines.getFirst().getType());
            } catch (IllegalArgumentException e) {
                log.error("Transaction type not found for transaction number {}", entry.getKey(), e);
                return Either.left(Problem.builder()
                        .withTitle("Transaction type not found")
                        .withDetail("Transaction type not found for transaction number " + entry.getKey())
                        .build());
            }
            LocalDate entryDate;
            try {
                entryDate = FlexibleDateParser.parse(transactionLines.getFirst().getDate());
            } catch (IllegalArgumentException e) {
                log.error("Transaction date parse error {}", entry.getKey(), e);
                return Either.left(Problem.builder()
                        .withTitle("Date parse exception")
                        .withDetail("transaction date can't be parsed " + entry.getKey())
                        .build());
            }
            Either<Problem, Set<TransactionItem>> convertItems = null;
            try {
                convertItems = convertToTransactionItem(transactionLines);
            } catch (Exception e) {
                log.error("Transaction items conversion failed for transaction number {}", entry.getKey(), e);
                return Either.left(Problem.builder()
                        .withTitle("Transaction items conversion failed")
                        .withDetail("Transaction items conversion failed for transaction number " + entry.getKey())
                        .build());
            }
            if (convertItems == null) {
                return Either.left(Problem.builder()
                        .withTitle("Transaction items conversion failed")
                        .withDetail("Transaction items conversion failed for transaction number " + entry.getKey())
                        .build());
            } else if (convertItems.isLeft()) {
                return Either.left(convertItems.getLeft());
            }

            transactions.add(Transaction.builder()
                    .id(Transaction.id(organisationId, entry.getKey()))
                    .internalTransactionNumber(entry.getKey())
                    .batchId(batchId)
                    .organisation(Organisation.builder().id(organisationId).build())
                    .transactionType(transactionType)
                    .entryDate(entryDate)
                    .accountingPeriod(YearMonth.from(entryDate))
                    .items(convertItems.get())
                    .build());
        }
        return Either.right(transactions);
    }

    private Either<Problem, Set<TransactionItem>> convertToTransactionItem(List<TransactionLine> transactionLines) {
        Set<TransactionItem> items = new HashSet<>();
        int lineNumber = 0;
        for (TransactionLine line : transactionLines) {
            TransactionItem.TransactionItemBuilder builder = TransactionItem.builder()
                    .id(TransactionItem.id(line.getTxNumber(), String.valueOf(lineNumber++)))
                    .fxRate(MoreBigDecimal.zeroForNull(BigDecimal.valueOf(Double.parseDouble(line.getFxRate()))))
                    .accountDebit(Optional.of(Account.builder()
                            .code(line.getDebitCode())
                            .name(Optional.ofNullable(line.getDebitName()))
                            .build())
                    )
                    .accountCredit(Optional.of(Account.builder()
                            .code(line.getCreditCode())
                            .name(Optional.ofNullable(line.getCreditName()))
                            .build())
                    )
                    .project(Optional.ofNullable(line.getProjectCode())
                            .map(code -> Project.builder()
                                    .customerCode(code)
                                    .build())
                    )
                    .document(getDocument(line));

            Either<Problem, Void> problemAddingAmounts = addAmountsAndOperationType(line, builder);
            if (problemAddingAmounts.isLeft()) return Either.left(problemAddingAmounts.getLeft());

            items.add(builder.build());
        }
        return Either.right(items);
    }

    private Either<Problem, Void> addAmountsAndOperationType(TransactionLine line, TransactionItem.TransactionItemBuilder builder) {
        try {
            OperationType operationType;
            BigDecimal amountLcy;
            BigDecimal amountFcy;

            if (StringUtils.isNoneBlank(line.getAmountLCYDebit()) && StringUtils.isNoneBlank(line.getAmountLCYCredit()) ||
                    StringUtils.isNoneBlank(line.getAmountFCYDebit()) && StringUtils.isNoneBlank(line.getAmountFCYCredit())) {
                // Error when both amounts are non-zero
                log.error("Both debit and credit amounts are non-zero for transaction: {}", line.getTxNumber());
                return Either.left(Problem.builder()
                        .withTitle("Both debit and credit amounts are non-zero")
                        .withDetail("Both debit and credit amounts are non-zero for transaction: " + line.getTxNumber())
                        .build());
            } else if (StringUtils.isNoneBlank(line.getAmountLCYDebit()) || StringUtils.isNoneBlank(line.getAmountFCYDebit())) {
                operationType = OperationType.DEBIT;
                amountLcy = MoreBigDecimal.zeroForNull(BigDecimal.valueOf(Double.parseDouble(line.getAmountLCYDebit())));
                amountFcy = MoreBigDecimal.zeroForNull(BigDecimal.valueOf(Double.parseDouble(line.getAmountFCYDebit())));
            } else if (StringUtils.isNoneBlank(line.getAmountLCYCredit()) || StringUtils.isNoneBlank(line.getAmountFCYCredit())) {
                operationType = OperationType.CREDIT;
                amountLcy = MoreBigDecimal.zeroForNull(BigDecimal.valueOf(Double.parseDouble(line.getAmountLCYCredit())));
                amountFcy = MoreBigDecimal.zeroForNull(BigDecimal.valueOf(Double.parseDouble(line.getAmountFCYCredit())));
            } else {
                log.info("Skipping transaction line with zero amounts for transaction: {}", line.getTxNumber());
                // Create a zero amount item.
                operationType = OperationType.DEBIT;
                amountLcy = BigDecimal.ZERO;
                amountFcy = BigDecimal.ZERO;
            }
            builder.amountLcy(amountLcy);
            builder.amountFcy(amountFcy);
            builder.operationType(operationType);
        } catch (NumberFormatException e) {
            log.error("Error parsing amount in transaction line", e);
            return Either.left(Problem.builder()
                    .withTitle("Error parsing amount")
                    .withDetail("Error parsing amount in transaction line")
                    .build());
        }
        return Either.right(null);
    }

    private Optional<Document> getDocument(TransactionLine line) {
        return Optional.of(Document.builder()
                .currency(Currency.builder().customerCode(line.getCurrency()).build())
                .number(line.getDocumentNumber())
                .vat(Optional.of(Vat.builder()
                        .customerCode(line.getVatCode())
                        .rate(Optional.of(BigDecimal.valueOf(Double.parseDouble(line.getVatRate()))))
                        .build()))
                .build());
    }

}
