package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.LOB;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.CORE_CURRENCY_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.ORGANISATION_DATA_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.CoreCurrencyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@RequiredArgsConstructor
public class OrganisationConversionTaskItem implements PipelineTaskItem {

    private final OrganisationPublicApi organisationPublicApi;
    private final CoreCurrencyRepository coreCurrencyRepository;

    @Override
    public void run(TransactionEntity tx) {
        val organisationId = tx.getOrganisation().getId();
        val organisationM = organisationPublicApi.findByOrganisationId(organisationId);

        if (organisationM.isEmpty()) {
            val v = TransactionViolation.builder()
                    .code(ORGANISATION_DATA_NOT_FOUND)
                    .txItemId(tx.getId())
                    .severity(ERROR)
                    .source(LOB)
                    .processorModule(this.getClass().getSimpleName())
                    .bag(
                            Map.of(
                                    "transactionNumber", tx.getTransactionInternalNumber()
                            )
                    )
                    .build();

            tx.addViolation(v);
        }

        val organisation = organisationM.orElseThrow();

        val coreCurrencyM = coreCurrencyRepository.findByCurrencyId(organisation.getCurrencyId());

        if (coreCurrencyM.isEmpty()) {
            val v = TransactionViolation.builder()
                    .code(CORE_CURRENCY_NOT_FOUND)
                    .txItemId(tx.getId())
                    .severity(ERROR)
                    .source(LOB)
                    .processorModule(this.getClass().getSimpleName())
                    .bag(
                            Map.of(
                                    "currencyId", organisation.getCurrencyId(),
                                    "transactionNumber", tx.getTransactionInternalNumber()
                            )
                    )
                    .build();

            tx.addViolation(v);
        }

        tx.setTransactionApproved(organisation.isPreApproveTransactionsEnabled());
        tx.setLedgerDispatchApproved(organisation.isPreApproveTransactionsDispatchEnabled());
        tx.setOrganisation(tx.getOrganisation().toBuilder()
                .name(organisation.getName())
                .taxIdNumber(organisation.getTaxIdNumber())
                .countryCode(organisation.getCountryCode())
                .currencyId(organisation.getCurrencyId())
                .adminEmail(organisation.getAdminEmail())
                .build()
        );
    }

}
