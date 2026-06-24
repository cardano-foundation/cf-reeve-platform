package org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.items;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source.LOB;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.COST_CENTER_DATA_NOT_FOUND;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR;

import java.util.Map;
import java.util.Optional;

import lombok.val;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;

public class CostCenterConversionTaskItem implements PipelineTaskItem {

    private final boolean enabled;
    private final OrganisationPublicApiIF organisationPublicApi;

    public CostCenterConversionTaskItem(OrganisationPublicApiIF organisationPublicApi) {
        this(true, organisationPublicApi);
    }

    public CostCenterConversionTaskItem(boolean enabled, OrganisationPublicApiIF organisationPublicApi) {
        this.enabled = enabled;
        this.organisationPublicApi = organisationPublicApi;
    }

    @Override
    public void run(TransactionEntity tx) {
        if (!enabled) return;
        val organisationId = tx.getOrganisation().getId();

        for (val txItem : tx.getItems()) {
            val costCenterM = txItem.getCostCenter();

            if (costCenterM.isEmpty()) {
                continue;
            }

            val costCenter = costCenterM.orElseThrow();
            val customerCode = costCenter.getCustomerCode();

            val costCenterMappingM = organisationPublicApi.findCostCenter(organisationId, customerCode);

            if (costCenterMappingM.isEmpty()) {
                val v = TransactionViolation.builder()
                        .code(COST_CENTER_DATA_NOT_FOUND)
                        .txItemId(txItem.getId())
                        .severity(ERROR)
                        .source(LOB)
                        .processorModule(this.getClass().getSimpleName())
                        .bag(
                                Map.of(
                                        "customerCode", customerCode,
                                        "transactionNumber", tx.getInternalTransactionNumber()
                                )
                        )
                        .build();

                tx.addViolation(v);

                continue;
            }

            val costCenterMapping = costCenterMappingM.orElseThrow();

            txItem.setCostCenter(Optional.of(costCenter.toBuilder()
                    .customerCode(customerCode)
                    .name(costCenterMapping.getName())
                    .build())
            );
        }

    }

}
