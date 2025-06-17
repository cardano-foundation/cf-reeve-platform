package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.ERP;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.LOB;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.*;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Vat;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Document;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.CoreCurrencyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;

@RequiredArgsConstructor
public class DocumentConversionTaskItem implements PipelineTaskItem {

    private final OrganisationPublicApiIF organisationPublicApi;
    private final CoreCurrencyRepository coreCurrencyRepository;

    @Override
    public void run(TransactionEntity tx) {
        val organisationId = tx.getOrganisation().getId();

        for (val txItem : tx.getItems()) {
            val documentM = txItem.getDocument();

            documentM.ifPresent(document -> processDocument(organisationId, tx, txItem, document));
        }
    }

    private void processDocument(String organisationId,
                                 TransactionEntity tx,
                                 TransactionItemEntity txItem,
                                 Document document) {
        val enrichedVatM = enrichVat(organisationId, tx, txItem, document);
        val enrichedCoreCurrencyM = enrichCurrency(organisationId, tx, txItem, document);

        updateDocument(txItem, document, enrichedCoreCurrencyM, enrichedVatM);
    }

    private Optional<Vat> enrichVat(String organisationId,
                                    TransactionEntity tx,
                                    TransactionItemEntity txItem,
                                    Document document) {
        /** Always recalculate the value. */
        return document.getVat()
                .flatMap(vat -> {
                    Optional<OrganisationVat> vatM = organisationPublicApi.findOrganisationByVatAndCode(organisationId, vat.getCustomerCode());

                    return vatM.map(v -> new Vat(vat.getCustomerCode(), Optional.of(v.getRate())))
                            .or(() -> {

                                addViolation(tx, txItem, VAT_DATA_NOT_FOUND, Map.of("customerCode", vat.getCustomerCode(), "transactionNumber", tx.getTransactionInternalNumber()));
                                // We save the wrong value to allow the reprocess read it and compare it against the database
                                return Optional.of(new Vat(vat.getCustomerCode(), Optional.empty()));
                            });
                });
    }

    private Optional<CoreCurrency> enrichCurrency(String organisationId,
                                                  TransactionEntity tx,
                                                  TransactionItemEntity txItem,
                                                  Document document) {
        val customerCurrencyCode = document.getCurrency().getCustomerCode();

        if (isBlank(customerCurrencyCode)) {
            tx.addViolation(TransactionViolation.builder()
                    .txItemId(txItem.getId())
                    .code(CURRENCY_DATA_NOT_FOUND)
                    .severity(ERROR)
                    .source(ERP)
                    .processorModule(getClass().getSimpleName())
                    .bag(Map.of("customerCode", customerCurrencyCode, "transactionNumber", tx.getTransactionInternalNumber()))
                    .build());

            return Optional.empty();
        }

        val organisationCurrencyM = organisationPublicApi.findCurrencyByCustomerCurrencyCode(organisationId, customerCurrencyCode);

        if (organisationCurrencyM.isEmpty()) {
            addViolation(tx, txItem, CURRENCY_DATA_NOT_FOUND, Map.of("customerCode", customerCurrencyCode, "transactionNumber", tx.getTransactionInternalNumber()));
        }

        return organisationCurrencyM.flatMap(orgCurrency -> {
            return coreCurrencyRepository.findByCurrencyId(orgCurrency.getCurrencyId())
                    .or(() -> {
                        addViolation(tx, txItem, CORE_CURRENCY_NOT_FOUND, Map.of("currencyId", orgCurrency.getCurrencyId(), "transactionNumber", tx.getTransactionInternalNumber()));

                        return Optional.empty();
                    });
        });
    }

    private void updateDocument(TransactionItemEntity txItem,
                                Document document,
                                Optional<CoreCurrency> enrichedCoreCurrencyM,
                                Optional<Vat> enrichedVatM) {
        val updatedDocument = document.toBuilder()
                .currency(document.getCurrency().toBuilder()
                        .id(enrichedCoreCurrencyM.map(CoreCurrency::toExternalId).orElse(null))
                        .build())
                .vat(getUpdatedVat(document, enrichedVatM))
                .build();

        txItem.setDocument(Optional.of(updatedDocument));
    }

    @Nullable
    private org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Vat getUpdatedVat(Document document,
                                                                                                    Optional<Vat> enrichedVatM) {
        if (document.getVat().isEmpty()) {
            return null;
        }

        val vatInDocument = document.getVat().orElseThrow();

        return enrichedVatM.map(v -> {
            return vatInDocument.toBuilder()
                    .rate(v.getRate()
                            .orElse(null))
                    .build();
        }).orElse(null);
    }

    private void addViolation(TransactionEntity tx,
                              TransactionItemEntity txItem,
                              TransactionViolationCode code,
                              Map<String, Object> bag) {
        val violation = TransactionViolation.builder()
                .txItemId(txItem.getId())
                .code(code)
                .severity(ERROR)
                .source(LOB)
                .processorModule(getClass().getSimpleName())
                .bag(bag)
                .build();
        tx.addViolation(violation);
    }

}
