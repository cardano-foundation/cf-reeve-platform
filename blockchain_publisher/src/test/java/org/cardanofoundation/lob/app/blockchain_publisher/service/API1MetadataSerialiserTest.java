package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type.VENDOR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType.FxRevaluation;
import static org.cardanofoundation.lob.app.blockchain_publisher.service.API1MetadataSerialiser.VERSION;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.Set;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadataList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.*;
import org.cardanofoundation.lob.app.support.calc.BigDecimals;

class API1MetadataSerialiserTest {

    private Clock fixedClock;

    private API1MetadataSerialiser api1MetadataSerialiser;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2023-06-01T10:15:30.00Z"), ZoneId.of("UTC"));
        api1MetadataSerialiser = new API1MetadataSerialiser(fixedClock);
    }

    @Test
    void testSerialiseToMetadataMap() {
        // Given
        YearMonth accountingPeriod = YearMonth.of(2023, 2);

        String organisationId = "org123";
        Organisation organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Test Organisation");
        organisation.setTaxIdNumber("123456789");
        organisation.setCurrencyId("USD");
        organisation.setCountryCode("US");

        TransactionEntity transaction = new TransactionEntity();
        transaction.setId("tx123");
        transaction.setInternalNumber("1");
        transaction.setBatchId("batch1");
        transaction.setTransactionType(FxRevaluation);
        transaction.setEntryDate(LocalDate.now(fixedClock));
        transaction.setAccountingPeriod(accountingPeriod);
        transaction.setOrganisation(organisation);

        TransactionItemEntity item1 = new TransactionItemEntity();
        item1.setId("item1");
        item1.setAmountFcy(new BigDecimal("100.00"));
        item1.setFxRate(new BigDecimal("1.0"));
        item1.setDocument(Document.builder()
                .num("doc1")
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build())
                .counterparty(Counterparty.builder()
                        .customerCode("CP 000001")
                        .type(VENDOR)
                        .build()
                )
                .vat(Vat.builder()
                        .customerCode("CH-VH-3.8")
                        .rate(BigDecimal.valueOf(0.038))
                        .build()
                )
                .build()
        );
        item1.setProject(Project.builder()
                .customerCode("AN 000001 2023")
                .name("Summit")
                .build()
        );
        item1.setCostCenter(CostCenter.builder()
                .customerCode("CC 000001")
                .name("Cost Center")
                .build()
        );

        TransactionItemEntity item2 = new TransactionItemEntity();
        item2.setId("item2");
        item2.setAmountFcy(new BigDecimal("200.00"));
        item2.setFxRate(new BigDecimal("1.0"));
        item2.setDocument(Document.builder()
                .num("doc2")
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build()).build()
        );

        TransactionItemEntity item3 = new TransactionItemEntity();
        item3.setId("item3");
        item3.setAmountFcy(new BigDecimal("300.00"));
        item3.setFxRate(new BigDecimal("1.0"));
        item3.setDocument(Document.builder()
                .num("doc3")
                .currency(Currency.builder()
                        .customerCode("USD")
                        .build())
                .build()
        );

        transaction.setItems(Set.of(item1, item2, item3));

        Set<TransactionEntity> transactions = Set.of(transaction);
        long creationSlot = 12345;

        // When
        MetadataMap result = api1MetadataSerialiser.serialiseToMetadataMap(organisationId, transactions, creationSlot);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("metadata")).isInstanceOf(MetadataMap.class);
        MetadataMap metadata = (MetadataMap) result.get("metadata");

        assertThat(metadata.get("creation_slot")).isEqualTo(BigInteger.valueOf(creationSlot));
        assertThat(metadata.get("timestamp")).isEqualTo("2023-06-01T10:15:30Z");
        assertThat(metadata.get("version")).isEqualTo(VERSION);

        assertThat(result.get("org")).isInstanceOf(MetadataMap.class);
        MetadataMap orgMap = (MetadataMap) result.get("org");

        assertThat(orgMap.get("id")).isEqualTo(organisation.getId());
        assertThat(orgMap.get("name")).isEqualTo(organisation.getName());
        assertThat(orgMap.get("tax_id_number")).isEqualTo(organisation.getTaxIdNumber());
        assertThat(orgMap.get("currency_id")).isEqualTo(organisation.getCurrencyId());
        assertThat(orgMap.get("country_code")).isEqualTo(organisation.getCountryCode());

        assertThat(result.get("data")).isInstanceOf(CBORMetadataList.class);
        CBORMetadataList txsList = (CBORMetadataList) result.get("data");

        assertThat(txsList.size()).isSameAs(1);
        MetadataMap txMap = (MetadataMap) txsList.getValueAt(0);

        assertThat(txMap.get("id")).isEqualTo(transaction.getId());
        assertThat(txMap.get("number")).isEqualTo(transaction.getInternalNumber());
        assertThat(txMap.get("batch_id")).isEqualTo(transaction.getBatchId());
        assertThat(txMap.get("type")).isEqualTo(transaction.getTransactionType().name());
        assertThat(txMap.get("date")).isEqualTo(transaction.getEntryDate().toString());
        assertThat(txMap.get("accounting_period")).isEqualTo(accountingPeriod.toString());

        assertThat(txMap.get("items")).isInstanceOf(CBORMetadataList.class);
        CBORMetadataList itemsList = (CBORMetadataList) txMap.get("items");

        assertThat(itemsList.size()).isSameAs(3);

        for (int i = 0; i < itemsList.size(); i++) {
            MetadataMap itemMap = (MetadataMap) itemsList.getValueAt(i);
            TransactionItemEntity item = transaction.getItems().stream()
                    .filter(it -> it.getId().equals(itemMap.get("id")))
                    .findFirst().orElseThrow();

            assertThat(itemMap.get("id")).isEqualTo(item.getId());
            assertThat(itemMap.get("amount")).isEqualTo(BigDecimals.normaliseString(item.getAmountFcy()));
            assertThat(itemMap.get("fx_rate")).isEqualTo(BigDecimals.normaliseString(item.getFxRate()));
            assertThat(itemMap.get("document")).isInstanceOf(MetadataMap.class);
        }

        MetadataMap itemMap1 = assertContainsItem("item1", itemsList);
        MetadataMap itemMap2 = assertContainsItem("item2", itemsList);
        MetadataMap itemMap3 = assertContainsItem("item3", itemsList);

        assertThat(itemMap1.get("id")).isEqualTo("item1");
        assertThat(itemMap1.get("amount")).isEqualTo(BigDecimals.normaliseString(item1.getAmountFcy()));
        assertThat(itemMap1.get("fx_rate")).isEqualTo(BigDecimals.normaliseString(item1.getFxRate()));
        assertThat(itemMap1.get("document")).isInstanceOf(MetadataMap.class);
        assertThat(itemMap1.get("project")).isInstanceOf(MetadataMap.class); // only for item 1
        assertThat(itemMap1.get("cost_center")).isInstanceOf(MetadataMap.class); // only for item 1

        MetadataMap documentMap = (MetadataMap) itemMap1.get("document");
        assertThat(documentMap.get("number")).isEqualTo("doc1");
        assertThat(documentMap.get("currency")).isInstanceOf(MetadataMap.class);
        assertThat(documentMap.get("counterparty")).isInstanceOf(MetadataMap.class);
        assertThat(documentMap.get("vat")).isInstanceOf(MetadataMap.class);
        MetadataMap counterParty = (MetadataMap) documentMap.get("counterparty");
        assertThat(counterParty.get("cust_code")).isEqualTo("CP 000001");
        assertThat(counterParty.get("type")).isEqualTo("VENDOR");

        MetadataMap vat = (MetadataMap) documentMap.get("vat");
        assertThat(vat.get("cust_code")).isEqualTo("CH-VH-3.8");
        assertThat(vat.get("rate")).isEqualTo("0.038");

        MetadataMap projectMap = (MetadataMap) itemMap1.get("project");
        assertThat(projectMap.get("cust_code")).isEqualTo("AN 000001 2023");
        assertThat(projectMap.get("name")).isEqualTo("Summit");

        MetadataMap costCenterMap = (MetadataMap) itemMap1.get("cost_center");
        assertThat(costCenterMap.get("cust_code")).isEqualTo("CC 000001");
        assertThat(costCenterMap.get("name")).isEqualTo("Cost Center");

        assertThat(itemMap2.get("id")).isEqualTo(item2.getId());
        assertThat(itemMap2.get("amount")).isEqualTo(BigDecimals.normaliseString(item2.getAmountFcy()));
        assertThat(itemMap2.get("fx_rate")).isEqualTo(BigDecimals.normaliseString(item2.getFxRate()));
        assertThat(itemMap2.get("document")).isInstanceOf(MetadataMap.class);

        assertThat(itemMap3.get("id")).isEqualTo(item3.getId());
        assertThat(itemMap3.get("amount")).isEqualTo(BigDecimals.normaliseString(item3.getAmountFcy()));
        assertThat(itemMap3.get("fx_rate")).isEqualTo(BigDecimals.normaliseString(item3.getFxRate()));
        assertThat(itemMap3.get("document")).isInstanceOf(MetadataMap.class);
    }

    private MetadataMap assertContainsItem(String id, CBORMetadataList items) {
        for (int i = 0; i < items.size(); i++) {
            MetadataMap itemMap = (MetadataMap) items.getValueAt(i);

            if (itemMap.get("id").equals(id)) {
                return itemMap;
            }
        }

        throw new AssertionError("Item with id %s not found".formatted(id));
    }

}
