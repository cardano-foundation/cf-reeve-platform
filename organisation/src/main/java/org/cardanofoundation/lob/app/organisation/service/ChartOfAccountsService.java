package org.cardanofoundation.lob.app.organisation.service;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
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
    public OrganisationChartOfAccountView updateChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Either<OrganisationChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId);
        if (organisationAvaliable.isLeft()) return organisationAvaliable.getLeft();

        Either<OrganisationChartOfAccountView, Void> referenceCodeAvailable = isReferenceCodeAvailable(orgId, chartOfAccountUpdate);
        if (referenceCodeAvailable.isLeft()) return referenceCodeAvailable.getLeft();

        Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType> subType = isSubTypeAvailable(orgId, chartOfAccountUpdate);
        if (subType.isLeft()) return subType.getLeft();

        Either<OrganisationChartOfAccountView, Void> parentCodeAvailable = isParentCodeAvailable(orgId, chartOfAccountUpdate);
        if (parentCodeAvailable.isLeft()) return parentCodeAvailable.getLeft();

        Either<OrganisationChartOfAccountView, OrganisationChartOfAccount> chartOfAccountOpt = isChartOfAccountAvailable(orgId, chartOfAccountUpdate);
        if (chartOfAccountOpt.isLeft()) return chartOfAccountOpt.getLeft();

        OrganisationChartOfAccount chartOfAccount = chartOfAccountOpt.get();
        return updateAndSaveChartOfAccount(chartOfAccountUpdate, subType, chartOfAccount);

    }

    private Either<OrganisationChartOfAccountView, OrganisationChartOfAccount> isChartOfAccountAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<OrganisationChartOfAccount> chartOfAccountOpt = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode());
        if (chartOfAccountOpt.isEmpty()) {
            return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("CHART_OF_ACCOUNT_NOT_FOUND")
                    .withDetail(STR."Unable to find the chart of account with code :\{chartOfAccountUpdate.getCustomerCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build()));
        }
        return Either.right(chartOfAccountOpt.get());
    }

    private Either<OrganisationChartOfAccountView, Void> isOrganisationAvaliable(String orgId) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build()));
        }
        return Either.right(null);
    }

    private Either<OrganisationChartOfAccountView, Void> isReferenceCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ReferenceCode> referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode());
        if (referenceCode.isEmpty()) {
            return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find event ref code: \{chartOfAccountUpdate.getEventRefCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build()));
        }
        return Either.right(null);
    }

    private Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType> isSubTypeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<OrganisationChartOfAccountSubType> subType = organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType());
        return subType.<Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType>>map(Either::right)
                .orElseGet(() -> Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                .withTitle("SUBTYPE_NOT_FOUND")
                .withDetail(STR."Unable to find subtype code :\{chartOfAccountUpdate.getSubType()}")
                .withStatus(Status.NOT_FOUND)
                .build())));
    }

    Either<OrganisationChartOfAccountView, Void> isParentCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        if (chartOfAccountUpdate.getParentCustomerCode() != null && !chartOfAccountUpdate.getParentCustomerCode().isEmpty()) {
            Optional<OrganisationChartOfAccount> parentChartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode());
            if (parentChartOfAccount.isEmpty()) {
                return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                        .withTitle("PARENT_ACCOUNT_NOT_FOUND")
                        .withDetail(STR."Unable to find the parent chart of account with code :\{chartOfAccountUpdate.getParentCustomerCode()}")
                        .withStatus(Status.NOT_FOUND)
                        .build()));
            }
        }
        return Either.right(null);
    }

    @Transactional
    public OrganisationChartOfAccountView insertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Either<OrganisationChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId);
        if (organisationAvaliable.isLeft()) return organisationAvaliable.getLeft();

        Either<OrganisationChartOfAccountView, Void> referenceCodeAvailable = isReferenceCodeAvailable(orgId, chartOfAccountUpdate);
        if (referenceCodeAvailable.isLeft()) return referenceCodeAvailable.getLeft();

        Either<OrganisationChartOfAccountView, Void> parentCodeAvailable = isParentCodeAvailable(orgId, chartOfAccountUpdate);
        if (parentCodeAvailable.isLeft()) return parentCodeAvailable.getLeft();

        Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType> subTypeAvailable = isSubTypeAvailable(orgId, chartOfAccountUpdate);
        if (subTypeAvailable.isLeft()) return subTypeAvailable.getLeft();

        Optional<OrganisationChartOfAccount> chartOfAccountOpt = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode());
        if (chartOfAccountOpt.isPresent()) {
            return OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("CHART_OF_ACCOUNT_ALREADY_EXISTS")
                    .withDetail(STR."The chart of account with code :\{chartOfAccountUpdate.getCustomerCode()} already exists")
                    .withStatus(Status.CONFLICT)
                    .build());
        }

        OrganisationChartOfAccount chartOfAccount = OrganisationChartOfAccount.builder()
                .id(new OrganisationChartOfAccount.Id(orgId, chartOfAccountUpdate.getCustomerCode()))
                .build();

        return updateAndSaveChartOfAccount(chartOfAccountUpdate, subTypeAvailable, chartOfAccount);

    }

    @Deprecated
    @Transactional
    public OrganisationChartOfAccountView upsertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Either<OrganisationChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId);
        if (organisationAvaliable.isLeft()) return organisationAvaliable.getLeft();

        Either<OrganisationChartOfAccountView, Void> referenceCodeAvailable = isReferenceCodeAvailable(orgId, chartOfAccountUpdate);
        if (referenceCodeAvailable.isLeft()) return referenceCodeAvailable.getLeft();

        Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType> subType = isSubTypeAvailable(orgId, chartOfAccountUpdate);
        if (subType.isLeft()) return subType.getLeft();

        Either<OrganisationChartOfAccountView, Void> parentCodeAvailable = isParentCodeAvailable(orgId, chartOfAccountUpdate);
        if (parentCodeAvailable.isLeft()) return parentCodeAvailable.getLeft();

        OrganisationChartOfAccount chartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()).orElse(
                OrganisationChartOfAccount.builder()
                        .id(new OrganisationChartOfAccount.Id(orgId, chartOfAccountUpdate.getCustomerCode()))

                        .build()
        );

        return updateAndSaveChartOfAccount(chartOfAccountUpdate, subType, chartOfAccount);

    }

    private OrganisationChartOfAccountView updateAndSaveChartOfAccount(ChartOfAccountUpdate chartOfAccountUpdate, Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType> subType, OrganisationChartOfAccount chartOfAccount) {
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
