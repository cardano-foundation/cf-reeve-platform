package org.cardanofoundation.lob.app.organisation;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.organisation.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.service.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrganisationPublicApi implements OrganisationPublicApiIF {

    private final OrganisationService organisationService;
    private final OrganisationCurrencyService organisationCurrencyService;
    private final OrganisationVatService organisationVatService;
    private final CostCenterService costCenterService;
    private final ProjectCodeService projectCodeService;
    private final ChartOfAccountsService chartOfAccountsService;
    private final AccountEventService accountEventService;

    public List<Organisation> listAll() {
        return organisationService.findAll();
    }

    public Optional<Organisation> findByOrganisationId(String id) {
        return organisationService.findById(id);
    }

    public Optional<OrganisationCurrency> findCurrencyByCustomerCurrencyCode(String organisationId,
                                                                             String customerCurrencyCode) {
        return organisationCurrencyService.findByOrganisationIdAndCode(organisationId, customerCurrencyCode);
    }

    public Optional<OrganisationVat> findOrganisationByVatAndCode(String organisationId, String customerCode) {
        return organisationVatService.findByOrganisationAndCode(organisationId, customerCode);
    }

    public Optional<OrganisationCostCenter> findCostCenter(String organisationId, String customerCode) {
        return costCenterService.getCostCenter(organisationId, customerCode);
    }

    public Optional<OrganisationProject> findProject(String organisationId, String customerCode) {
        return projectCodeService.getProject(organisationId, customerCode);
    }

    public Optional<OrganisationChartOfAccount> getChartOfAccounts(String organisationId, String customerCode) {
        return chartOfAccountsService.getChartAccount(organisationId, customerCode);
    }

    public Optional<AccountEvent> findEventCode(String organisationId, String debitcustomerCode, String creditCustomerCode) {
        return accountEventService.findById(organisationId, debitcustomerCode, creditCustomerCode);
    }
}
