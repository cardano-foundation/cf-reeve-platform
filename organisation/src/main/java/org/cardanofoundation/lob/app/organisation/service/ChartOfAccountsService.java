package org.cardanofoundation.lob.app.organisation.service;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationChartOfAccountView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class ChartOfAccountsService {

    private final ChartOfAccountRepository chartOfAccountRepository;
    private final OrganisationChartOfAccountTypeRepository organisationChartOfAccountTypeRepository;
    private final OrganisationChartOfAccountSubTypeRepository organisationChartOfAccountSubTypeRepository;
    private final ReferenceCodeRepository referenceCodeRepository;

    public Optional<OrganisationChartOfAccount> getChartAccount(String organisationId, String customerCode) {
        return chartOfAccountRepository.findById(new OrganisationChartOfAccount.Id(organisationId, customerCode));
    }

    @Transactional
    public Set<OrganisationChartOfAccountType> getAllChartType(String organisationId) {
        return organisationChartOfAccountTypeRepository.findAllByOrganisationId(organisationId);
    }

    public Set<OrganisationChartOfAccount> getBySubTypeId(Long subType) {
        return chartOfAccountRepository.findAllByOrganisationIdSubTypeId(subType);
    }

    public Set<OrganisationChartOfAccountView> getAllChartOfAccount(String organisationId) {
        return chartOfAccountRepository.findAllByOrganisationId(organisationId).stream().map(OrganisationChartOfAccountView::build).collect(Collectors.toSet());
    }

    @Transactional
    public Optional<OrganisationChartOfAccountView> upsertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Optional<ReferenceCode> referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode());
        if (referenceCode.isEmpty()) {
            return Optional.empty();
        }

        OrganisationChartOfAccount chartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()).orElse(
                OrganisationChartOfAccount.builder()
                        .id(new OrganisationChartOfAccount.Id(orgId, chartOfAccountUpdate.getCustomerCode()))

                        .build()
        );

        Optional<OrganisationChartOfAccountSubType> subType = organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType());

        if (subType.isEmpty()) {
            return Optional.empty();
        }
        chartOfAccount.setEventRefCode(chartOfAccountUpdate.getEventRefCode());
        chartOfAccount.setName(chartOfAccountUpdate.getName());
        chartOfAccount.setRefCode("DEPRECATED");
        chartOfAccount.setSubType(subType.get());
        chartOfAccountRepository.save(chartOfAccount);

        return Optional.ofNullable(OrganisationChartOfAccountView.build(chartOfAccount));
    }

}
