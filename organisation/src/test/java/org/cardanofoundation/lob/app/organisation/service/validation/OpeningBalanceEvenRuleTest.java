package org.cardanofoundation.lob.app.organisation.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;
import org.cardanofoundation.lob.app.organisation.domain.core.OrganisationViolation;
import org.cardanofoundation.lob.app.organisation.domain.core.OrganisationViolationType;
import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.service.validation.rules.OpeningBalanceEvenRule;

@ExtendWith(MockitoExtension.class)
public class OpeningBalanceEvenRuleTest {

    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;

    @InjectMocks
    private OpeningBalanceEvenRule openingBalanceEvenRule;

    private final String orgId = "OrgId";

    @Test
    void validate_noViolations() {
        OrganisationChartOfAccount chartOfAccount1 = mock(OrganisationChartOfAccount.class);
        OrganisationChartOfAccount chartOfAccount2 = mock(OrganisationChartOfAccount.class);
        Organisation organisation = mock(Organisation.class);

        when(organisation.getId()).thenReturn(orgId);

        when(chartOfAccountRepository.findAllByOrganisationId(orgId)).thenReturn(Set.of(
                chartOfAccount1,
                chartOfAccount2
        ));

        when(chartOfAccount1.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                        .balanceFCY(BigDecimal.TEN)
                        .balanceLCY(BigDecimal.TWO)
                        .balanceType(OperationType.DEBIT)
                .build());
        when(chartOfAccount2.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                        .balanceFCY(BigDecimal.TEN)
                        .balanceLCY(BigDecimal.TWO)
                        .balanceType(OperationType.CREDIT)
                .build());

        Optional<List<OrganisationViolation>> validate = openingBalanceEvenRule.validate(organisation);
        assertThat(validate).isPresent();
        assertThat(validate.get()).isEmpty();
    }

    @Test
    void validate_fcyNotEqual() {
        OrganisationChartOfAccount chartOfAccount1 = mock(OrganisationChartOfAccount.class);
        OrganisationChartOfAccount chartOfAccount2 = mock(OrganisationChartOfAccount.class);
        Organisation organisation = mock(Organisation.class);

        when(organisation.getId()).thenReturn(orgId);

        when(chartOfAccountRepository.findAllByOrganisationId(orgId)).thenReturn(Set.of(
                chartOfAccount1,
                chartOfAccount2
        ));

        when(chartOfAccount1.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                .balanceFCY(BigDecimal.TWO)
                .balanceLCY(BigDecimal.TWO)
                .balanceType(OperationType.DEBIT)
                .build());
        when(chartOfAccount2.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                .balanceFCY(BigDecimal.TEN)
                .balanceLCY(BigDecimal.TWO)
                .balanceType(OperationType.CREDIT)
                .build());

        Optional<List<OrganisationViolation>> validate = openingBalanceEvenRule.validate(organisation);
        assertThat(validate).isPresent();
        assertThat(validate.get().size()).isEqualTo(1);
        List<OrganisationViolation> violations = validate.get();
        assertThat(violations.get(0).getType()).isEqualTo(OrganisationViolationType.OPENING_BALANCE_FCY_NOT_ZERO);
    }

    @Test
    void validate_lcyNotEqual() {
        OrganisationChartOfAccount chartOfAccount1 = mock(OrganisationChartOfAccount.class);
        OrganisationChartOfAccount chartOfAccount2 = mock(OrganisationChartOfAccount.class);
        Organisation organisation = mock(Organisation.class);

        when(organisation.getId()).thenReturn(orgId);

        when(chartOfAccountRepository.findAllByOrganisationId(orgId)).thenReturn(Set.of(
                chartOfAccount1,
                chartOfAccount2
        ));

        when(chartOfAccount1.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                .balanceFCY(BigDecimal.TEN)
                .balanceLCY(BigDecimal.TEN)
                .balanceType(OperationType.DEBIT)
                .build());
        when(chartOfAccount2.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                .balanceFCY(BigDecimal.TEN)
                .balanceLCY(BigDecimal.TWO)
                .balanceType(OperationType.CREDIT)
                .build());

        Optional<List<OrganisationViolation>> validate = openingBalanceEvenRule.validate(organisation);
        assertThat(validate).isPresent();
        assertThat(validate.get().size()).isEqualTo(1);
        List<OrganisationViolation> violations = validate.get();
        assertThat(violations.get(0).getType()).isEqualTo(OrganisationViolationType.OPENING_BALANCE_LCY_NOT_ZERO);
    }

    @Test
    void validate_bothNotEqual() {
        OrganisationChartOfAccount chartOfAccount1 = mock(OrganisationChartOfAccount.class);
        OrganisationChartOfAccount chartOfAccount2 = mock(OrganisationChartOfAccount.class);
        Organisation organisation = mock(Organisation.class);

        when(organisation.getId()).thenReturn(orgId);

        when(chartOfAccountRepository.findAllByOrganisationId(orgId)).thenReturn(Set.of(
                chartOfAccount1,
                chartOfAccount2
        ));

        when(chartOfAccount1.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                .balanceFCY(BigDecimal.TWO)
                .balanceLCY(BigDecimal.TEN)
                .balanceType(OperationType.DEBIT)
                .build());
        when(chartOfAccount2.getOpeningBalance()).thenReturn(OpeningBalance.builder()
                .balanceFCY(BigDecimal.TEN)
                .balanceLCY(BigDecimal.TWO)
                .balanceType(OperationType.CREDIT)
                .build());

        Optional<List<OrganisationViolation>> validate = openingBalanceEvenRule.validate(organisation);
        assertThat(validate).isPresent();
        assertThat(validate.get().size()).isEqualTo(2);
        List<OrganisationViolation> violations = validate.get();
        assertThat(violations.get(0).getType()).isEqualTo(OrganisationViolationType.OPENING_BALANCE_LCY_NOT_ZERO);
        assertThat(violations.get(1).getType()).isEqualTo(OrganisationViolationType.OPENING_BALANCE_FCY_NOT_ZERO);
    }

}
