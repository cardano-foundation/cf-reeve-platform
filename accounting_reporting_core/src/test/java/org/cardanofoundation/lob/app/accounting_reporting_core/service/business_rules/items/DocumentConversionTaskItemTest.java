package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency.IsoStandard.ISO_4217;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.CURRENCY_DATA_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.VAT_DATA_NOT_FOUND;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Optional;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.CoreCurrencyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;

@ExtendWith(MockitoExtension.class)
class DocumentConversionTaskItemTest {

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @Mock
    private CoreCurrencyRepository coreCurrencyRepository;

    private DocumentConversionTaskItem documentConversionTaskItem;

    @BeforeEach
    void setup() {
        this.documentConversionTaskItem = new DocumentConversionTaskItem(organisationPublicApi, coreCurrencyRepository);
    }

    @Test
    void testVatDataNotFoundAddsViolation() {
        String txId = "1";
        String txInternalNumber = "txn123";
        String organisationId = "org1";
        String customerCode = "custCode";

        Document document = Document.builder()
                .vat(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Vat.builder()
                        .customerCode(customerCode)
                        .build())
                .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency.builder()
                        .customerCode("USD")
                        .build())
                .build();

        TransactionItemEntity txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setDocument(Optional.of(document));

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(txId);
        transaction.setTransactionInternalNumber(txInternalNumber);
        transaction.setOrganisation(Organisation.builder()
                .id(organisationId)
                .build()
        );
        transaction.setItems(items);

        when(organisationPublicApi.findOrganisationByVatAndCode(organisationId, customerCode)).thenReturn(Optional.empty());

        documentConversionTaskItem.run(transaction);

        assertThat(transaction.getViolations()).isNotEmpty();
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == VAT_DATA_NOT_FOUND);
    }

    @Test
    void testCurrencyNotFoundAddsViolation() {
        String txId = "1";
        String txInternalNumber = "txn123";
        String organisationId = "org1";
        String customerCurrencyCode = "USD";

        Document document = Document.builder()
                .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency.builder()
                        .customerCode(customerCurrencyCode)
                        .build())
                .build();

        TransactionItemEntity txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setDocument(Optional.of(document));

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(txId);
        transaction.setTransactionInternalNumber(txInternalNumber);
        transaction.setOrganisation(Organisation.builder()
                .id(organisationId)
                .build());
        transaction.setItems(items);

        when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(organisationId, customerCurrencyCode)).thenReturn(Optional.empty());

        documentConversionTaskItem.run(transaction);

        assertThat(transaction.getViolations()).isNotEmpty();
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == CURRENCY_DATA_NOT_FOUND);
    }

    @Test
    void testSuccessfulDocumentConversion() {
        String txId = "1";
        String txInternalNumber = "txn123";
        String organisationId = "org1";
        String customerCurrencyCode = "USD";
        String customerVatCode = "VAT123";
        String currencyId = "ISO_4217:USD";

        Document document = Document.builder()
                .vat(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Vat.builder()
                        .customerCode(customerVatCode)
                        .build())
                .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency.builder()
                        .customerCode(customerCurrencyCode)
                        .build())
                .build();

        TransactionItemEntity txItem = new TransactionItemEntity();
        txItem.setDocument(Optional.of(document));

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(txId);
        transaction.setTransactionInternalNumber(txInternalNumber);
        transaction.setOrganisation(Organisation.builder()
                .id(organisationId)
                .build());
        transaction.setItems(items);

        when(organisationPublicApi.findOrganisationByVatAndCode(organisationId, customerVatCode))
                .thenReturn(Optional.of(Vat.builder()
                                .id(new Vat.Id(organisationId, customerVatCode))
                                .rate(BigDecimal.valueOf(0.2))
                        .build()));

        when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(organisationId, customerCurrencyCode))
                .thenReturn(Optional.of(new Currency(new Currency.Id(organisationId, customerCurrencyCode), currencyId)));

        when(coreCurrencyRepository.findByCurrencyId(currencyId))
                .thenReturn(Optional.of(CoreCurrency.builder()
                                .currencyISOStandard(ISO_4217)
                                .name("USD Dollar")
                                .currencyISOCode("USD")
                        .build()));

        documentConversionTaskItem.run(transaction);

        assertThat(transaction.getViolations()).isEmpty();
        assertThat(transaction.getItems()).hasSize(1);
        assertThat(transaction.getItems().iterator().next().getDocument().orElseThrow().getCurrency().getId().orElseThrow()).isEqualTo(currencyId);
        assertThat(transaction.getItems().iterator().next().getDocument().orElseThrow().getVat().orElseThrow().getRate().orElseThrow()).isEqualTo(BigDecimal.valueOf(0.2));
        assertThat(transaction.getItems().iterator().next().getDocument().orElseThrow().getCurrency().getCustomerCode()).isEqualTo("USD");
    }

    @Test
    void testDocumentConversionWithMultipleViolations() {
        String txId = "1";
        String txInternalNumber = "txn123";
        String organisationId = "org1";
        String customerCurrencyCode = "UNKNOWN_CURRENCY";
        String customerVatCode = "UNKNOWN_VAT";

        Document document = Document.builder()
                .vat(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Vat.builder()
                        .customerCode(customerVatCode)
                        .build())
                .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency.builder()
                        .customerCode(customerCurrencyCode)
                        .build())
                .build();

        TransactionItemEntity txItem = new TransactionItemEntity();
        txItem.setDocument(Optional.of(document));

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(txId);
        transaction.setTransactionInternalNumber(txInternalNumber);
        transaction.setOrganisation(Organisation.builder()
                .id(organisationId)
                .build());
        transaction.setItems(items);

        when(organisationPublicApi.findOrganisationByVatAndCode(organisationId, customerVatCode))
                .thenReturn(Optional.empty());

        when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(organisationId, customerCurrencyCode))
                .thenReturn(Optional.empty());

        documentConversionTaskItem.run(transaction);

        assertThat(transaction.getViolations()).hasSize(2);
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == VAT_DATA_NOT_FOUND);
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == CURRENCY_DATA_NOT_FOUND);
    }

    @Test
    void testDocumentConversionWithNoCurrencyInDocument() {
        String txId = "1";
        String txInternalNumber = "txn123";
        String organisationId = "org1";
        String customerVatCode = "UNKNOWN_VAT";

        Document document = Document.builder()
                .vat(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Vat.builder()
                        .customerCode(customerVatCode)
                        .build())
                .currency(org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Currency.builder()
                        .customerCode("")
                        .build())
                .build();

        TransactionItemEntity txItem = new TransactionItemEntity();
        txItem.setDocument(Optional.of(document));

        LinkedHashSet<TransactionItemEntity> items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId(txId);
        transaction.setTransactionInternalNumber(txInternalNumber);
        transaction.setOrganisation(Organisation.builder()
                .id(organisationId)
                .build());
        transaction.setItems(items);

        documentConversionTaskItem.run(transaction);

        assertThat(transaction.getViolations()).hasSize(2);
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == VAT_DATA_NOT_FOUND);
        assertThat(transaction.getViolations()).anyMatch(v -> v.getCode() == CURRENCY_DATA_NOT_FOUND);
    }

}
