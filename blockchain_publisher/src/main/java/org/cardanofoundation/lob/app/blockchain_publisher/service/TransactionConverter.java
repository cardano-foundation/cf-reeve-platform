package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static java.util.stream.Collectors.toSet;

import java.util.Optional;
import java.util.Set;

import jakarta.persistence.OneToOne;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.*;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionConverter {

    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;
    private final OrganisationPublicApi organisationPublicApi;

    public TransactionEntity convertToDbDetached(Transaction tx) {
        val transactionEntity = new TransactionEntity();
        transactionEntity.setId(tx.getId());
        transactionEntity.setInternalNumber(tx.getInternalTransactionNumber());
        transactionEntity.setBatchId(tx.getBatchId());
        transactionEntity.setTransactionType(tx.getTransactionType());
        transactionEntity.setOrganisation(convertOrganisation(tx.getOrganisation()));
        transactionEntity.setEntryDate(tx.getEntryDate());
        transactionEntity.setAccountingPeriod(tx.getAccountingPeriod());

        val publishStatus = blockchainPublishStatusMapper.convert(tx.getLedgerDispatchStatus());
        transactionEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .publishStatus(publishStatus)
                .build())
        );

        transactionEntity.setItems(convertTxItems(tx, transactionEntity));

        return transactionEntity;
    }

    private Set<TransactionItemEntity> convertTxItems(Transaction tx, TransactionEntity transactionEntity) {
        return tx.getItems()
                .stream()
                .map(tl -> convertToDbDetached(transactionEntity, tl))
                .collect(toSet());
    }

    private static Organisation convertOrganisation(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation org) {
        return Organisation.builder()
                .id(org.getId())
                .name(org.getName().orElseThrow())
                .countryCode(org.getCountryCode().orElseThrow())
                .taxIdNumber(org.getTaxIdNumber().orElseThrow())
                .currencyId(org.getCurrencyId())
                .build();
    }

    private static Document convertDocument(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Document doc) {
        return Document.builder()
                .num(doc.getNumber())
                .currency(Currency.builder()
                        .id(doc.getCurrency().getCoreCurrency().orElseThrow().toExternalId())
                        .customerCode(doc.getCurrency().getCustomerCode())
                        .build()
                )
                .vat(doc.getVat().map(vat -> Vat.builder()
                        .customerCode(vat.getCustomerCode())
                        .rate(vat.getRate().orElseThrow())
                        .build()).orElse(null))
                .counterparty(doc.getCounterparty().map(cp -> Counterparty.builder()
                        .customerCode(cp.getCustomerCode())
                        .type(cp.getType())
                        .build()).orElse(null))
                .build();
    }

    @OneToOne
    public TransactionItemEntity convertToDbDetached(TransactionEntity parent,
                                                     TransactionItem txItem) {
        val txItemEntity = new TransactionItemEntity();
        txItemEntity.setId(txItem.getId());
        txItemEntity.setTransaction(parent);

        txItemEntity.setAccountEvent(txItem.getAccountEvent().map(e -> AccountEvent.builder()
                        .code(e.getCode())
                        .name(e.getName()).build())
                .orElse(null)
        );

        txItemEntity.setFxRate(txItem.getFxRate());

        txItemEntity.setAmountFcy(txItem.getAmountFcy());

        txItemEntity.setDocument(convertDocument(txItem.getDocument().orElseThrow()));

        txItemEntity.setCostCenter(txItem.getCostCenter().map(cc -> {
            val ccBuilder = org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.CostCenter.builder();

            // If the cost center is not associated with the parent organisation, we do not set it.
            // Note: Only one parent level.
            Optional<CostCenter> costCenterS = organisationPublicApi.findCostCenter(parent.getOrganisation().getId(), cc.getCustomerCode());
            if (costCenterS.isPresent()) {
                CostCenter costCenter = costCenterS.get();
                if (costCenter.getParent().isPresent()) {
                    ccBuilder.customerCode(costCenter.getParent().get().getId().getCustomerCode());
                    ccBuilder.name(costCenter.getParent().get().getName());
                    return ccBuilder.build();
                }
            }
            cc.getExternalCustomerCode().ifPresent(ccBuilder::customerCode);
            cc.getName().ifPresent(ccBuilder::name);

            return ccBuilder.build();
        }).orElse(null));

        txItemEntity.setProject(txItem.getProject().map(pc -> Project.builder()
                .customerCode(pc.getExternalCustomerCode().orElseThrow())
                .name(pc.getName().orElseThrow())
                .build()).orElse(null)
        );

        return txItemEntity;
    }

}
