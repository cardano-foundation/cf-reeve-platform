package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import static org.zalando.problem.Status.BAD_REQUEST;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
            "reconciliationDate", "lastReconcilation.createdAt"
        );

    public static final Map<String, String> RECONCILATION_FIELD_MAPPINGS_VIOLATION = Map.of(
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
            Map.entry("vatRate", "document.vat.rate")
    );

    /**
     * Wrapper que valida y convierte un Pageable contra múltiples tipos de entidad.
     * Combina las reglas de ordenación de todas las validaciones exitosas.
     *
     * @param page          El Pageable original.
     * @param fieldMappings El mapa de mapeo de campos.
     * @param previousPageable    Una lista de clases de entidad contra las que validar.
     * @return Un Either conteniendo un Problema si falla, o el Pageable combinado si tiene éxito.
     */
    public Either<Problem, Pageable> convertPageables(Either<Problem, Pageable> previousPageable,Pageable page,
                                                      Map<String, String> fieldMappings,
                                                      Class<?> classType) {
        if (!page.getSort().isSorted()) {
            log.info("\n\n##### AH!\n");
            return Either.right(page);
        }

        if (previousPageable.isLeft()) {
            log.info("\n\n#####Es left el ANTERIOR\n");
            return previousPageable;
        }

        Either<Problem, Pageable> currentPageable = this.convertPageable(page, fieldMappings, classType);

        if (currentPageable.isLeft()) {
            log.info("\n\n#####Es left el ESTE\n");
            return previousPageable;
        }
        Sort combinedSort = previousPageable.get().getSort().and(currentPageable.get().getSort());

        Pageable mergedPageable = PageRequest.of(
                previousPageable.get().getPageNumber(),
                previousPageable.get().getPageSize(),
                combinedSort
        );
        return Either.right(mergedPageable);
    }

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
