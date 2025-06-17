package org.cardanofoundation.lob.app.organisation.service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.csv.ChartOfAccountUpdateCsv;
import org.cardanofoundation.lob.app.organisation.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationChartOfAccountView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

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
    private final CsvParser<ChartOfAccountUpdateCsv> csvParser;
    @PersistenceContext
    private EntityManager entityManager;

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

        Either<OrganisationChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId, chartOfAccountUpdate.getCustomerCode());
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
                    .withDetail("Unable to find the chart of account with code :%s".formatted(chartOfAccountUpdate.getCustomerCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), chartOfAccountUpdate.getCustomerCode()));
        }
        return Either.right(chartOfAccountOpt.get());
    }

    private Either<OrganisationChartOfAccountView, Void> isOrganisationAvaliable(String orgId, String customerCode) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(), customerCode));
        }
        return Either.right(null);
    }

    private Either<OrganisationChartOfAccountView, Void> isReferenceCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ReferenceCode> referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode());
        if (referenceCode.isEmpty()) {
            return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find event ref code: %s".formatted(chartOfAccountUpdate.getEventRefCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), chartOfAccountUpdate.getCustomerCode()));
        }
        return Either.right(null);
    }

    private Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType> isSubTypeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<OrganisationChartOfAccountSubType> subType = organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType());
        return subType.<Either<OrganisationChartOfAccountView, OrganisationChartOfAccountSubType>>map(Either::right)
                .orElseGet(() -> Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                .withTitle("SUBTYPE_NOT_FOUND")
                .withDetail("Unable to find subtype code :%s".formatted(chartOfAccountUpdate.getSubType()))
                .withStatus(Status.NOT_FOUND)
                .build(), chartOfAccountUpdate.getCustomerCode())));
    }

    Either<OrganisationChartOfAccountView, Void> isParentCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        if (chartOfAccountUpdate.getParentCustomerCode() != null && !chartOfAccountUpdate.getParentCustomerCode().isEmpty()) {
            Optional<OrganisationChartOfAccount> parentChartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode());
            if (parentChartOfAccount.isEmpty()) {
                return Either.left(OrganisationChartOfAccountView.createFail(Problem.builder()
                        .withTitle("PARENT_ACCOUNT_NOT_FOUND")
                        .withDetail("Unable to find the parent chart of account with code :%s".formatted(chartOfAccountUpdate.getParentCustomerCode()))
                        .withStatus(Status.NOT_FOUND)
                        .build(), chartOfAccountUpdate.getCustomerCode()));
            }
        }
        return Either.right(null);
    }

    @Transactional
    public OrganisationChartOfAccountView insertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Either<OrganisationChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId, chartOfAccountUpdate.getCustomerCode());
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
                    .withDetail("The chart of account with code :%s already exists".formatted(chartOfAccountUpdate.getCustomerCode()))
                    .withStatus(Status.CONFLICT)
                    .build(), chartOfAccountUpdate.getCustomerCode());
        }

        OrganisationChartOfAccount chartOfAccount = OrganisationChartOfAccount.builder()
                .id(new OrganisationChartOfAccount.Id(orgId, chartOfAccountUpdate.getCustomerCode()))
                .build();

        return updateAndSaveChartOfAccount(chartOfAccountUpdate, subTypeAvailable, chartOfAccount);

    }

    @Deprecated
    @Transactional
    public OrganisationChartOfAccountView upsertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Either<OrganisationChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId, chartOfAccountUpdate.getCustomerCode());
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

    @Transactional
    public Either<Set<Problem>, Set<OrganisationChartOfAccountView>> insertChartOfAccountByCsv(String orgId, MultipartFile file) {


        Either<Problem, List<ChartOfAccountUpdateCsv>> lists = csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class);

        if (lists.isLeft()) {
            return Either.left(Set.of(lists.getLeft()));
        }

        List<ChartOfAccountUpdateCsv> chartOfAccountUpdates = lists.get();
        Set<OrganisationChartOfAccountView> accountEventViews = new HashSet<>();
        for (ChartOfAccountUpdateCsv chartOfAccountUpdateCsv : chartOfAccountUpdates) {
            // A workAround to fill the nested object
            try {
                chartOfAccountUpdateCsv.fillOpeningBalance();
            } catch (IllegalArgumentException e) {
                Problem error = Problem.builder()
                        .withTitle("OPENING_BALANCE_ERROR")
                        .withDetail(e.getMessage())
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                accountEventViews.add(OrganisationChartOfAccountView.createFail(error , chartOfAccountUpdateCsv.getCustomerCode()));
                continue;
            }

            organisationChartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, chartOfAccountUpdateCsv.getType()).ifPresentOrElse(
                    type -> {
                        type.getSubTypes().stream().filter(subtype -> subtype.getName().equals(chartOfAccountUpdateCsv.getSubType())).findFirst().ifPresentOrElse(
                                subType -> chartOfAccountUpdateCsv.setSubType(String.valueOf(subType.getId())),
                                () -> {
                                    OrganisationChartOfAccountSubType subType = OrganisationChartOfAccountSubType.builder()
                                            .type(type)
                                            .name(chartOfAccountUpdateCsv.getSubType())
                                            .organisationId(orgId)
                                            .build();
                                    // currently needed, since we are
//                                    resetOrganisationChartOfAccountSubTypeSequence();
                                    OrganisationChartOfAccountSubType save = organisationChartOfAccountSubTypeRepository.save(subType);
                                    chartOfAccountUpdateCsv.setSubType(String.valueOf(save.getId()));
                                }
                        );
                    },
                    () -> {
                        OrganisationChartOfAccountSubType subType = saveTypeAndSubType(orgId, chartOfAccountUpdateCsv);
                        chartOfAccountUpdateCsv.setSubType(String.valueOf(subType.getId()));
                    }
            );

            OrganisationChartOfAccountView accountEventView = insertChartOfAccount(orgId, chartOfAccountUpdateCsv);
            accountEventViews.add(accountEventView);
        }
        return Either.right(accountEventViews);
    }

    private OrganisationChartOfAccountSubType saveTypeAndSubType(String orgId, ChartOfAccountUpdateCsv chartOfAccountUpdateCsv) {

        OrganisationChartOfAccountType organisationChartOfAccountType = OrganisationChartOfAccountType.builder()
                .organisationId(orgId)
                .name(chartOfAccountUpdateCsv.getType())
                .build();
        OrganisationChartOfAccountSubType organisationChartOfAccountSubType = OrganisationChartOfAccountSubType.builder()
                .organisationId(orgId)
                .name(chartOfAccountUpdateCsv.getSubType())
                .type(organisationChartOfAccountType)
                .build();
        organisationChartOfAccountType.setSubTypes(Set.of(organisationChartOfAccountSubType));
        OrganisationChartOfAccountType save = organisationChartOfAccountTypeRepository.save(organisationChartOfAccountType);
        return save.getSubTypes().stream().iterator().next();
    }
}
