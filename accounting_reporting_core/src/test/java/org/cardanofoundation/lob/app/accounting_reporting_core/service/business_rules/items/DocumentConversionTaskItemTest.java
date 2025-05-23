package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency.IsoStandard.ISO_4217;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.CURRENCY_DATA_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.VAT_DATA_NOT_FOUND;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Optional;

import lombok.val;

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
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCurrency;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;

@ExtendWith(MockitoExtension.class)
public class DocumentConversionTaskItemTest {

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @Mock
    private CoreCurrencyRepository coreCurrencyRepository;

    private DocumentConversionTaskItem documentConversionTaskItem;

    @BeforeEach
    public void setup() {
        this.documentConversionTaskItem = new DocumentConversionTaskItem(organisationPublicApi, coreCurrencyRepository);
    }

    @Test
    public void testVatDataNotFoundAddsViolation() {
        val txId = "1";
        val txInternalNumber = "txn123";
        val organisationId = "org1";
        val customerCode = "custCode";

        val document = Document.builder()
                .vat(Vat.builder()
                        .customerCode(customerCode)
                        .build())
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build())
                .build();

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setDocument(Optional.of(document));

        val items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        val transaction = new TransactionEntity();
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
    public void testCurrencyNotFoundAddsViolation() {
        val txId = "1";
        val txInternalNumber = "txn123";
        val organisationId = "org1";
        val customerCurrencyCode = "USD";

        val document = Document.builder()
                .currency(Currency.builder()
                        .customerCode(customerCurrencyCode)
                        .build())
                .build();

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));
        txItem.setDocument(Optional.of(document));

        val items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        val transaction = new TransactionEntity();
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
    public void testSuccessfulDocumentConversion() {
        val txId = "1";
        val txInternalNumber = "txn123";
        val organisationId = "org1";
        val customerCurrencyCode = "USD";
        val customerVatCode = "VAT123";
        val currencyId = "ISO_4217:USD";

        val document = Document.builder()
                .vat(Vat.builder()
                        .customerCode(customerVatCode)
                        .build())
                .currency(Currency.builder()
                        .customerCode(customerCurrencyCode)
                        .build())
                .build();

        val txItem = new TransactionItemEntity();
        txItem.setDocument(Optional.of(document));

        val items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        val transaction = new TransactionEntity();
        transaction.setId(txId);
        transaction.setTransactionInternalNumber(txInternalNumber);
        transaction.setOrganisation(Organisation.builder()
                .id(organisationId)
                .build());
        transaction.setItems(items);

        when(organisationPublicApi.findOrganisationByVatAndCode(organisationId, customerVatCode))
                .thenReturn(Optional.of(new OrganisationVat(new OrganisationVat.Id(organisationId, customerVatCode), BigDecimal.valueOf(0.2))));

        when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(organisationId, customerCurrencyCode))
                .thenReturn(Optional.of(new OrganisationCurrency(new OrganisationCurrency.Id(organisationId, customerCurrencyCode), currencyId)));

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
    public void testDocumentConversionWithMultipleViolations() {
        val txId = "1";
        val txInternalNumber = "txn123";
        val organisationId = "org1";
        val customerCurrencyCode = "UNKNOWN_CURRENCY";
        val customerVatCode = "UNKNOWN_VAT";

        val document = Document.builder()
                .vat(Vat.builder()
                        .customerCode(customerVatCode)
                        .build())
                .currency(Currency.builder()
                        .customerCode(customerCurrencyCode)
                        .build())
                .build();

        val txItem = new TransactionItemEntity();
        txItem.setDocument(Optional.of(document));

        val items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        val transaction = new TransactionEntity();
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
    public void testDocumentConversionWithNoCurrenciInDocument() {
        val txId = "1";
        val txInternalNumber = "txn123";
        val organisationId = "org1";
        val customerCurrencyCode = "UNKNOWN_CURRENCY";
        val customerVatCode = "UNKNOWN_VAT";

        val document = Document.builder()
                .vat(Vat.builder()
                        .customerCode(customerVatCode)
                        .build())
                .currency(Currency.builder()
                        .customerCode("")
                        .build())
                .build();

        val txItem = new TransactionItemEntity();
        txItem.setDocument(Optional.of(document));

        val items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem);

        val transaction = new TransactionEntity();
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
