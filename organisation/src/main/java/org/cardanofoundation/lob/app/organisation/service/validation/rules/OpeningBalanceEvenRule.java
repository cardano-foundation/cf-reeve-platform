package org.cardanofoundation.lob.app.organisation.service.validation.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;
import org.cardanofoundation.lob.app.organisation.domain.core.OrganisationViolation;
import org.cardanofoundation.lob.app.organisation.domain.core.OrganisationViolationType;
import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.service.validation.OrganisationValidationRule;

@Component
@Slf4j
@RequiredArgsConstructor
public class OpeningBalanceEvenRule implements OrganisationValidationRule {

    private ChartOfAccountRepository chartOfAccountRepository;

    @Override
    public Optional<List<OrganisationViolation>> validate(Organisation organisation) {
        List<OrganisationViolation> violations = new ArrayList<>();

        Set<OrganisationChartOfAccount> organisationChartOfAccounts = chartOfAccountRepository.findAllByOrganisationId(organisation.getId());
        BigDecimal totalOpeningBalanceLCY = BigDecimal.ZERO;
        BigDecimal totalOpeningBalanceFCY = BigDecimal.ZERO;
        for (OrganisationChartOfAccount chartOfAccount : organisationChartOfAccounts) {
            OpeningBalance openingBalance = chartOfAccount.getOpeningBalance();
            if (openingBalance == null) {
                continue;
            }
            if (openingBalance.getBalanceType() == OperationType.DEBIT) {
                totalOpeningBalanceLCY = totalOpeningBalanceLCY.add(chartOfAccount.getOpeningBalance().getBalanceLCY());
                totalOpeningBalanceFCY = totalOpeningBalanceFCY.add(chartOfAccount.getOpeningBalance().getBalanceFCY());
            } else {
                totalOpeningBalanceLCY = totalOpeningBalanceLCY.subtract(chartOfAccount.getOpeningBalance().getBalanceLCY());
                totalOpeningBalanceFCY = totalOpeningBalanceFCY.subtract(chartOfAccount.getOpeningBalance().getBalanceFCY());
            }
        }
        if (totalOpeningBalanceLCY.compareTo(BigDecimal.ZERO) != 0) {
            violations.add(OrganisationViolation.builder()
                    .message(STR."Total Opening Balance LCY is not zero. Total Opening Balance LCY: \{totalOpeningBalanceLCY}")
                    .type(OrganisationViolationType.OPENING_BALANCE_LCY_NOT_ZERO)
                    .build());
        }
        if (totalOpeningBalanceFCY.compareTo(BigDecimal.ZERO) != 0) {
            violations.add(OrganisationViolation.builder()
                    .message(STR."Total Opening Balance FCY is not zero. Total Opening Balance FCY: \{totalOpeningBalanceFCY}")
                    .type(OrganisationViolationType.OPENING_BALANCE_FCY_NOT_ZERO)
                    .build());
        }
        return Optional.of(violations);
    }
}
