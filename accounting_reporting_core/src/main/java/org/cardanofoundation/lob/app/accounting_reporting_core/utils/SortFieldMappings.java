package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import static org.zalando.problem.Status.BAD_REQUEST;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

// This class contains mappings if any needed for sorting entities
@Component
@RequiredArgsConstructor
public class SortFieldMappings {

        private final JpaSortFieldValidator jpaSortFieldValidator;

        public static final Map<String, String> TRANSACTION_ENTITY_FIELD_MAPPINGS = Map.of(
        "reconciliationSource", "reconcilation.source",
        "reconciliationSink", "reconcilation.sink",
        "reconciliationFinalStatus", "reconcilation.finalStatus",
        "dataSource", "extractorType",
        "status", "overallStatus",
        "statistic", "processingStatus",
        "validationStatus", "automatedValidationStatus"
        );

        public static final Map<String, String> RECONCILATION_FIELD_MAPPINGS = Map.of(
            "dataSource", "extractorType",
            "status", "overallStatus",
            "amountTotalLcy", "totalAmountLcy",
            "reconciliationSource", "reconcilation.source",
            "reconcilationSink", "reconcilation.sink",
            "reconciliationDate", "createdAt"
        );

        public static final Map<String, String> EXTRACTION_SEARCH_FIELD_MAPPINGS = Map.ofEntries(
                        Map.entry("transactionInternalNumber",
                                        "transaction.internalTransactionNumber"),
                        Map.entry("transactionID", "transaction.id"),
                        Map.entry("entryDate", "transaction.entryDate"),
                        Map.entry("transactionType", "transaction.transactionType"),
                        Map.entry("reconciliation", "transaction.reconcilation.finalStatus"),
                        Map.entry("accountDebitCode", "accountDebit.code"),
                        Map.entry("accountCreditCode", "accountCredit.code"),
                        Map.entry("accountDebitName", "accountDebit.name"),
                        Map.entry("accountCreditName", "accountCredit.name"),
                        Map.entry("accountDebitRefCode", "accountDebit.refCode"),
                        Map.entry("accountCreditRefCode", "accountCredit.refCode"),
                        Map.entry("costCenterCustomerCode", "costCenter.customerCode"),
                        Map.entry("costCenterName", "costCenter.name"),
                        Map.entry("projectCustomerCode", "project.customerCode"),
                        Map.entry("projectName", "project.name"),
                        Map.entry("accountEventCode", "accountEvent.code"),
                        Map.entry("accountEventName", "accountEvent.name"),
                        Map.entry("documentNum", "document.num"),
                        Map.entry("documentCurrencyCustomerCode", "document.currency.customerCode"),
                        Map.entry("vatRate", "document.vat.rate"),
                        Map.entry("blockChainHash", "transaction.ledgerDispatchReceipt.primaryBlockchainHash"),
                        Map.entry("vatCustomerCode", "document.vat.customerCode"),
                        Map.entry("parentCostCenterCustomerCode", "mappedCostCenter.parentCustomerCode"),
                        Map.entry("parentProjectCustomerCode", "mappedProject.parentCustomerCode"),
                        Map.entry("counterpartyCustomerCode", "document.counterparty.customerCode"),
                        Map.entry("counterpartyName", "document.counterparty.name"),
                        Map.entry("counterpartyType", "document.counterparty.type")
                );

        public Either<Problem, Pageable> convertPageable(Pageable page,
                        Map<String, String> fieldMappings, Class<?> classType) {
                if (page.getSort().isSorted()) {
                        Optional<Sort.Order> notSortableProperty =
                                        page.getSort().get().filter(order -> {
                                                String property = Optional
                                                                .ofNullable(fieldMappings.get(order
                                                                                .getProperty()))
                                                                .orElse(order.getProperty());

                                                return !jpaSortFieldValidator.isSortable(classType,
                                                                property);
                                        }).findFirst();

                        if (notSortableProperty.isPresent()) {
                                return Either.left(Problem.builder().withStatus(BAD_REQUEST)
                                                .withTitle("Invalid Sort Property")
                                                .withDetail("Invalid sort: " + notSortableProperty
                                                                .get().getProperty())
                                                .build());
                        }

                        Sort sort = Sort.by(page.getSort().get().map(order -> {
                                String property = Optional
                                                .ofNullable(fieldMappings.get(order.getProperty()))
                                                .orElse(order.getProperty());

                                // simple enum detection – you can swap for a static Set
                                boolean isEnum = false;
                                try {
                                        isEnum = classType.getDeclaredField(property).getType()
                                                        .isEnum();
                                } catch (NoSuchFieldException ignored) {
                                }

                                if (isEnum) {
                                        return JpaSort.unsafe(order.getDirection(),
                                                        "function('enum_to_text', " + property
                                                                        + ")")
                                                        .iterator().next();
                                }

                                return new Sort.Order(order.getDirection(), property);
                        }).toList());

                        return Either.right(PageRequest.of(page.getPageNumber(), page.getPageSize(),
                                        sort));
                }

                return Either.right(page);
        }
}
