package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static java.util.stream.Collectors.groupingBy;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError.Code.ADAPTER_ERROR;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType.*;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.CodeMappingType.ORGANISATION;
import static org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreString.normaliseString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Currency;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FinancialPeriodSource;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.Transactions;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreBigDecimal;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util.MoreString;
import org.cardanofoundation.lob.app.support.collections.Optionals;

@RequiredArgsConstructor
@Slf4j
public class TransactionConverter {

    private final Validator validator;
    private final CodesMappingService codesMappingService;
    private final PreprocessorService preprocessorService;
    private final TransactionTypeMapper transactionTypeMapper;
    private final String netsuiteInstanceId;
    private final FinancialPeriodSource financialPeriodSource;

    public Either<FatalError, Transactions> convert(String organisationId,
                                                    String batchId,
                                                    List<TxLine> txLines) {
        ArrayList<TxLine> searchResultsByOrganisation = new ArrayList<>();
        LinkedHashSet<Transaction> transactions = new LinkedHashSet<>();

        for (TxLine txLine : txLines) {
            Either<FatalError, String> localOrgIdE = organisationId(txLine);

            if (localOrgIdE.isEmpty()) {
                return Either.left(localOrgIdE.getLeft());
            }

            String localOrgId = localOrgIdE.get();

            if (localOrgId.equals(organisationId)) {
                searchResultsByOrganisation.add(txLine);
            }
        }

        Map<String, List<TxLine>> searchResultItemsPerTransactionNumber = searchResultsByOrganisation
                .stream()
                .collect(groupingBy(TxLine::transactionNumber));

        for (Map.Entry<String, List<TxLine>> entry : searchResultItemsPerTransactionNumber.entrySet()) {
            List<TxLine> transactionLevelTxLines = entry.getValue();
            Either<FatalError, Optional<Transaction>> transactionE = createTransactionFromSearchResultItems(organisationId, batchId, transactionLevelTxLines);

            if (transactionE.isEmpty()) {
                return Either.left(transactionE.getLeft());
            }

            transactionE.get().ifPresent(transactions::add);
        }

        return Either.right(new Transactions(organisationId, transactions));
    }

    private Either<FatalError, Optional<Transaction>> createTransactionFromSearchResultItems(String organisationId,
                                                                                             String batchId,
                                                                                             List<TxLine> txLines
    ) {
        if (txLines.isEmpty()) {
            return Either.right(Optional.empty());
        }

        TxLine firstTxLine = txLines.getFirst();
        String txId = Transaction.id(organisationId, firstTxLine.transactionNumber());

        Either<FatalError, TransactionType> transTypeE = transactionType(organisationId, txId, firstTxLine);
        if (transTypeE.isEmpty()) {
            return Either.left(transTypeE.getLeft());
        }
        TransactionType transactionType = transTypeE.get();

        LocalDate txDate = firstTxLine.date();
        String internalTransactionNumber = firstTxLine.transactionNumber();
        BigDecimal fxRate = firstTxLine.exchangeRate();
        YearMonth accountingPeriod = financialPeriod(firstTxLine);

        LinkedHashSet<TransactionItem> txItems = new LinkedHashSet<>();

        for (TxLine txLine : txLines) {
            Set<ConstraintViolation<TxLine>> validationIssues = validator.validate(txLine);
            boolean isValid = validationIssues.isEmpty();
            if (!isValid) {
                Map<String, Object> bag = Map.of(
                        "organisationId", organisationId,
                        "txId", txId,
                        "internalTransactionNumber", txLine.transactionNumber(),
                        "validationIssues", humanReadable(validationIssues)
                );

                log.error("Validation failed for transaction: {}", bag);

                return Either.left(new FatalError(ADAPTER_ERROR, "TRANSACTIONS_VALIDATION_ERROR", bag));
            }

            Optional<String> accountCreditCodeM = accountCreditCode(organisationId, txLine.accountMain());

            OperationType operationType;
            BigDecimal amountLcy;
            BigDecimal amountFcy;
            if(txLine.amountDebit() != null && txLine.amountCredit() != null) {
                // Error when both amounts are non-zero
                log.error("Both debit and credit amounts are non-zero for transaction: {}", txId);
                return Either.left(new FatalError(ADAPTER_ERROR, "TRANSACTIONS_VALIDATION_ERROR", Map.of()));
            } else if(txLine.amountDebit() != null) {
                operationType = OperationType.DEBIT;
                amountLcy = MoreBigDecimal.zeroForNull(txLine.amountDebit());
                amountFcy = MoreBigDecimal.zeroForNull(txLine.amountDebitForeignCurrency());
            } else if(txLine.amountCredit() != null) {
                operationType = OperationType.CREDIT;
                amountLcy = MoreBigDecimal.zeroForNull(txLine.amountCredit());
                amountFcy = MoreBigDecimal.zeroForNull(txLine.amountCreditForeignCurrency());
            } else {
                log.info("Skipping transaction line with zero amounts for transaction: {}", txId);
                // Create a zero amount item.
                operationType = OperationType.DEBIT;
                amountLcy = BigDecimal.ZERO;
                amountFcy = BigDecimal.ZERO;
            }

            Optional<String> costCenterM = costCenterCode(organisationId, txLine);
            Optional<String> projectCodeM = projectCode(organisationId, txLine);

            Optional<Document> documentM = convertDocument(organisationId, txLine);

            TransactionItem txItem = TransactionItem.builder()
                    .id(TransactionItem.id(txId, txLine.lineID().toString()))
                    .accountDebit(Optionals.zip(normaliseString(txLine.name()), normaliseString(txLine.number()), (accountDebitName, accountDebitCode) -> {
                        return Account.builder()
                                .name(Optional.of(accountDebitName))
                                .code(accountDebitCode)
                                .build();
                    }))
                    .accountCredit(accountCreditCodeM.map(accountCreditCode -> {
                                return Account.builder()
                                        .code(accountCreditCode)
                                        .build();
                            })
                    )
                    .project(projectCodeM.map(pc -> Project.builder()
                            .customerCode(pc)
                            .build()
                    ))
                    .costCenter(costCenterM.map(cc -> CostCenter.builder()
                            .customerCode(cc)
                            .build()
                    ))
                    .document(documentM)
                    .fxRate(fxRate)
                    .amountLcy(amountLcy)
                    .amountFcy(amountFcy)
                    .operationType(operationType)
                    .build();

            txItems.add(txItem);
        }

        return Either.right(Optional.of(Transaction.builder()
                .id(txId)
                .internalTransactionNumber(internalTransactionNumber)
                .entryDate(txDate)
                .batchId(batchId)
                .transactionType(transactionType)
                .accountingPeriod(accountingPeriod)
                .organisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation.builder()
                        .id(organisationId)
                        .build()
                )
                .items(txItems)
                .build())
        );
    }

    private static List<Map<String, Object>> humanReadable(Set<ConstraintViolation<TxLine>> validationIssues) {
        return validationIssues.stream().map(c -> {
            return Map.of(
                    "bean", c.getRootBean().getClass().getName(),
                    "msg", c.getMessage(),
                    "property", c.getPropertyPath().toString(),
                    "invalidValue", c.getInvalidValue()
            );
        }).toList();
    }

    private YearMonth financialPeriod(TxLine txLine) {
        return switch (financialPeriodSource) {
            case IMPLICIT -> YearMonth.from(txLine.date());
            case EXPLICIT -> {
                LocalDate endDate = txLine.endDate();

                yield YearMonth.from(endDate);
            }
        };
    }

    private Either<FatalError, TransactionType> transactionType(String organisationId,
                                                                String txId,
                                                                TxLine txLine) {
        Optional<TransactionType> transactionTypeM = transactionTypeMapper.apply(txLine.type());

        if (transactionTypeM.isEmpty()) {
            Map<String, Object> bag = Map.<String, Object>of(
                    "organisationId", organisationId,
                    "internalTransactionNumber", txLine.transactionNumber(),
                    "txId", txId,
                    "type", txLine.type()
            );

            return Either.left(new FatalError(ADAPTER_ERROR, "TRANSACTION_TYPE_NOT_YET_KNOWN", bag));
        }

        return Either.right(transactionTypeM.orElseThrow());
    }

    private Optional<Document> convertDocument(String organisationId, TxLine txLine) {
        Optional<String> documentNumberM = normaliseString(txLine.documentNumber());

        if (documentNumberM.isPresent()) {
            String documentNumber = documentNumberM.orElseThrow();

            Optional<String> taxItemM = normaliseString(txLine.taxItem())
                    .map(String::trim);

            Optional<String> vatCodeM = Optional.<String>empty();
            if (taxItemM.isPresent()) {
                Either<Problem, String> vatCodeE = preprocessorService.preProcess(taxItemM.orElseThrow(), VAT);

                if (vatCodeE.isEmpty()) {
                    log.warn("Conversion failed for vatCode: {} in organisation: {}", taxItemM.orElseThrow(), organisationId);

                    vatCodeM = Optional.of(taxItemM.orElseThrow());
                } else {
                    vatCodeM = Optional.of(vatCodeE.get());
                }
            }

            return Optional.of(Document.builder()
                    .number(documentNumber)
                    .currency(Currency.builder()
                            .customerCode(txLine.currencySymbol())
                            .build())
                    .vat(vatCodeM.map(cc -> Vat.builder()
                            .customerCode(cc)
                            .build()))
                    .counterparty(convertCounterparty(txLine))
                    .build()
            );
        }

        return Optional.empty();
    }

    private static Optional<Counterparty> convertCounterparty(TxLine txLine) {
        return normaliseString(txLine.counterPartyId()).map(customerCode -> Counterparty.builder()
                .customerCode(customerCode)
                .type(VENDOR) // TODO CF hardcoded for now
                .name(normaliseString(txLine.counterPartyName()))
                .build());
    }

    private Optional<String> accountCreditCode(String organisationId,
                                               String accountMain) {
        Optional<String> accountCodeCreditM = normaliseString(accountMain);

        if (accountCodeCreditM.isPresent()) {
            String accountCodeCreditText = accountCodeCreditM.orElseThrow();

            Either<Problem, String> accountCreditCodeE = preprocessorService.preProcess(accountCodeCreditM.orElseThrow(), CHART_OF_ACCOUNT);

            if (accountCreditCodeE.isEmpty()) {
                log.warn("Conversion failed for accountCodeCredit: {} in organisation: {}", accountCodeCreditText, organisationId);

                return Optional.of(accountCodeCreditText);
            }

            String accountCreditCode = accountCreditCodeE.get();

            return Optional.of(accountCreditCode);
        }

        return Optional.empty();
    }

    private Optional<String> costCenterCode(String organisationId,
                                            TxLine txLine) {
        Optional<String> costCenterM = MoreString.normaliseString(txLine.costCenter());

        if (costCenterM.isPresent()) {
            String costCenterText = costCenterM.orElseThrow();

            Either<Problem, String> costCenterE = preprocessorService.preProcess(costCenterText, COST_CENTER);

            if (costCenterE.isEmpty()) {
                log.warn("Conversion failed for costCenter: {} in organisation: {}", costCenterText, organisationId);

                return Optional.of(costCenterText);
            }
            String costCenter = costCenterE.get();

            return Optional.of(costCenter);
        }

        return Optional.empty();
    }

    private Either<FatalError, String> organisationId(TxLine txLine) {
        Optional<String> organisationIdM = codesMappingService.getCodeMapping(netsuiteInstanceId, txLine.subsidiary(), ORGANISATION);

        if (organisationIdM.isEmpty()) {
            Map<String, Object> bag = Map.<String, Object>of(
                    "netsuiteInstanceId", netsuiteInstanceId,
                    "subsidiary", txLine.subsidiary()
            );

            return Either.left(new FatalError(ADAPTER_ERROR, "ORGANISATION_NOT_IMPORTED", bag));
        }

        return Either.right(organisationIdM.orElseThrow());
    }

    private Optional<String> projectCode(String organisationId,
                                         TxLine txLine) {
        Optional<String> projectM = normaliseString(txLine.project());

        if (projectM.isPresent()) {
            String projectText = projectM.orElseThrow();

            Either<Problem, String> projectCodeE = preprocessorService.preProcess(projectM.orElseThrow(), PROJECT);

            if (projectCodeE.isEmpty()) {
                log.warn("Conversion failed for projectCode: {} in organisation: {}", projectText, organisationId);

                return Optional.of(projectText);
            }

            String projectCode = projectCodeE.get();

            return Optional.of(projectCode);
        }

        return Optional.empty();
    }

}
