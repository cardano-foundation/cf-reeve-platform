package org.cardanofoundation.lob.app.organisation.service;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.*;
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
    private final OrganisationService organisationService;

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
        return chartOfAccountRepository.findAllByOrganisationId(organisationId).stream().map(OrganisationChartOfAccountView::createSuccess).collect(Collectors.toSet());
    }

    @Transactional
    public OrganisationChartOfAccountView upsertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        Optional<ReferenceCode> referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode());
        if (referenceCode.isEmpty()) {
            return OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find event ref code: \{chartOfAccountUpdate.getEventRefCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        Optional<OrganisationChartOfAccountSubType> subType = organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType());

        if (subType.isEmpty()) {
            return OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("SUBTYPE_NOT_FOUND")
                    .withDetail(STR."Unable to find subtype code :\{chartOfAccountUpdate.getSubType()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        if (chartOfAccountUpdate.getParentCustomerCode() != null && !chartOfAccountUpdate.getParentCustomerCode().isEmpty()) {
            Optional<OrganisationChartOfAccount> parentChartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode());
            if (parentChartOfAccount.isEmpty()) {
                return OrganisationChartOfAccountView.createFail(Problem.builder()
                        .withTitle("PARENT_ACCOUNT_NOT_FOUND")
                        .withDetail(STR."Unable to find the parent chart of account with code :\{chartOfAccountUpdate.getParentCustomerCode()}")
                        .withStatus(Status.NOT_FOUND)
                        .build());
            }
        }

        OrganisationChartOfAccount chartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()).orElse(
                OrganisationChartOfAccount.builder()
                        .id(new OrganisationChartOfAccount.Id(orgId, chartOfAccountUpdate.getCustomerCode()))

                        .build()
        );

        chartOfAccount.setEventRefCode(chartOfAccountUpdate.getEventRefCode());
        chartOfAccount.setName(chartOfAccountUpdate.getName());
        chartOfAccount.setRefCode(chartOfAccountUpdate.getRefCode());
        chartOfAccount.setSubType(subType.get());
        chartOfAccount.setParentCustomerCode(chartOfAccountUpdate.getParentCustomerCode() == null || chartOfAccountUpdate.getParentCustomerCode().isEmpty() ? null : chartOfAccountUpdate.getParentCustomerCode());
        chartOfAccount.setCurrencyId(chartOfAccountUpdate.getCurrency());
        chartOfAccount.setCounterParty(chartOfAccountUpdate.getCounterParty());
        chartOfAccount.setActive(chartOfAccountUpdate.getActive());
        chartOfAccount.setOpeningBalance(chartOfAccountUpdate.getOpeningBalance());

        OrganisationChartOfAccount chartOfAccountResult = chartOfAccountRepository.save(chartOfAccount);
        return OrganisationChartOfAccountView.createSuccess(chartOfAccountResult);

    }

}
