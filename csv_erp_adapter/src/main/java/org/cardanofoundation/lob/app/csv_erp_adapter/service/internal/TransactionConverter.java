package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static java.util.stream.Collectors.groupingBy;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CostCenter;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty;
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
import org.cardanofoundation.lob.app.support.date.FlexibleDateParser;

@Service("csvTransactionConverter")
@Slf4j
@RequiredArgsConstructor
public class TransactionConverter {

    NumberFormat format = NumberFormat.getInstance(Locale.US);

    public Either<Problem, List<Transaction>> convertToTransaction(String organisationId, String batchId, List<TransactionLine> lines) {
        Map<String, List<TransactionLine>> collect = lines.stream().collect(groupingBy(transactionLine -> Optional.ofNullable(transactionLine.getTxNumber()).orElse("")));
        List<Transaction> transactions = new ArrayList<>();
        for (Map.Entry<String, List<TransactionLine>> entry : collect.entrySet()) {
            List<TransactionLine> transactionLines = entry.getValue();
            if (transactionLines.isEmpty()) {
                continue; // skipping
            }
            String transactionId = Transaction.id(organisationId, entry.getKey());

            TransactionType transactionType;
            try {
                transactionType = Optional.ofNullable(transactionLines.getFirst().getType()).map(TransactionType::valueOf).orElse(TransactionType.Unknown);
            } catch (IllegalArgumentException e) {
                // incase the transaction type is not found in enum
                log.error("Transaction type not found for transaction number {}", entry.getKey(), e);
                transactionType = TransactionType.Unknown;
            }
            LocalDate entryDate;
            try {
                if (Optional.ofNullable(transactionLines.getFirst().getDate()).isEmpty()) {
                    continue; // skipping transactions without date
                }
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
                    .id(transactionId)
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
                    .id(TransactionItem.id(Optional.ofNullable(line.getTxNumber()).orElse(""),
                            String.valueOf(lineNumber++)))
                    .fxRate(Optional.ofNullable(line.getFxRate())
                            .map(rate -> BigDecimal.valueOf(Double.parseDouble(rate)))
                            .orElse(BigDecimal.ZERO))
                    .costCenter(Optional.ofNullable(line.getCostCenterCode())
                            .map(costCenterCode -> CostCenter.builder()
                                    .customerCode(costCenterCode).build()))
                    .accountDebit(Optional.ofNullable(line.getDebitCode()).map(code -> Account.builder()
                            .code(code)
                            .name(Optional.ofNullable(line.getDebitName()))
                            .build())
                    )
                    .accountCredit(Optional.ofNullable(line.getCreditCode()).map(code -> Account.builder()
                            .code(code)
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
                String debitLcyStr = line.getAmountLCYDebit();
                String debitFcyStr = line.getAmountFCYDebit();
                amountLcy = debitLcyStr != null ? BigDecimal.valueOf(format.parse(debitLcyStr).doubleValue()) : BigDecimal.ZERO;
                amountFcy = debitFcyStr != null ? BigDecimal.valueOf(format.parse(debitFcyStr).doubleValue()) : BigDecimal.ZERO;
            } else if (StringUtils.isNoneBlank(line.getAmountLCYCredit()) || StringUtils.isNoneBlank(line.getAmountFCYCredit())) {
                operationType = OperationType.CREDIT;
                String creditLcyStr = line.getAmountLCYCredit();
                String creditFcyStr = line.getAmountFCYCredit();
                amountLcy = creditLcyStr != null ? BigDecimal.valueOf(format.parse(creditLcyStr).doubleValue()) : BigDecimal.ZERO;
                amountFcy = creditFcyStr != null ? BigDecimal.valueOf(format.parse(creditFcyStr).doubleValue()) : BigDecimal.ZERO;
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
        } catch (NumberFormatException | ParseException e) {
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
                .currency(Currency.builder()
                        .customerCode(Optional.ofNullable(line.getCurrency())
                                .orElse(""))
                        .build())
                .number(line.getDocumentNumber())
                .vat(Optional.ofNullable(line.getVatCode()).map(vatCode -> Vat.builder()
                        .customerCode(vatCode)
                        .rate(Optional.ofNullable(line.getVatRate()).map(vatRate -> BigDecimal.valueOf(Double.parseDouble(vatRate))))
                        .build()))
                .counterparty(Optional.ofNullable(line.getCounterPartyCode()).map(counterPartyCode ->
                        Counterparty.builder()
                                .customerCode(counterPartyCode)
                                .name(Optional.ofNullable(line.getCounterPartyName()))
                                .build()))
                .build());
    }

}
