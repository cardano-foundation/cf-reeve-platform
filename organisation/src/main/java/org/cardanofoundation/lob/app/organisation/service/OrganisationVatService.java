package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationVatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationVatView;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationVatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganisationVatService {

    private final OrganisationVatRepository organisationVatRepository;
    private final CsvParser<OrganisationVatUpdate> csvParser;

    public Optional<OrganisationVat> findByOrganisationAndCode(String organisationId, String customerCode) {
        return organisationVatRepository.findById(new OrganisationVat.Id(organisationId, customerCode));
    }

    public List<OrganisationVatView> findAllByOrganisationId(String organisationId) {
        return organisationVatRepository.findAllByOrganisationId(organisationId).stream()
                .map(OrganisationVatView::convertFromEntity)
                .toList();
    }

    @Transactional
    public OrganisationVatView insert(String organisationId, OrganisationVatUpdate organisationVatUpdate) {

        Optional<OrganisationVat> organisationVat = organisationVatRepository.findById(new OrganisationVat.Id(organisationId, organisationVatUpdate.getCustomerCode()));

        if (organisationVat.isPresent()) {
            return OrganisationVatView.createFail(organisationVatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("ORGANISATION_VAT_ALREADY_EXISTS")
                    .withDetail("The organisation vat with code :%s already exists".formatted(organisationVatUpdate.getCustomerCode()))
                    .withStatus(Status.CONFLICT)
                    .build());
        }

        if (organisationVatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(organisationVatUpdate.getCountryCode())) {
            return OrganisationVatView.createFail(organisationVatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("COUNTRY_CODE_NOT_FOUND")
                    .withDetail("The organisation vat country_code with code %s do not exists".formatted(organisationVatUpdate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        OrganisationVat organisationVatEntity = OrganisationVat.builder()
                .id(new OrganisationVat.Id(organisationId, organisationVatUpdate.getCustomerCode()))
                .rate(organisationVatUpdate.getRate())
                .description(organisationVatUpdate.getDescription())
                .countryCode(organisationVatUpdate.getCountryCode() == null || organisationVatUpdate.getCountryCode().isEmpty() ? null : organisationVatUpdate.getCountryCode())
                .active(organisationVatUpdate.getActive())
                .build();

        return OrganisationVatView.convertFromEntity(organisationVatRepository.save(organisationVatEntity));
    }

    @Transactional
    public OrganisationVatView update(String organisationId, OrganisationVatUpdate organisationVatUpdate) {

        Optional<OrganisationVat> organisationVat = organisationVatRepository.findById(new OrganisationVat.Id(organisationId, organisationVatUpdate.getCustomerCode()));

        if (organisationVat.isEmpty()) {
            return OrganisationVatView.createFail(organisationVatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("ORGANISATION_VAT_DO_NOT_EXISTS")
                    .withDetail("The organisation vat with code %s do not exists".formatted(organisationVatUpdate.getCustomerCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        if (organisationVatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(organisationVatUpdate.getCountryCode())) {
            return OrganisationVatView.createFail(organisationVatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("COUNTRY_CODE_NOT_FOUND")
                    .withDetail("The organisation vat country_code with code %s do not exists".formatted(organisationVatUpdate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        OrganisationVat organisationVatEntity = organisationVat.get();

        organisationVatEntity.setRate(organisationVatUpdate.getRate());
        organisationVatEntity.setDescription(organisationVatUpdate.getDescription());
        organisationVatEntity.setCountryCode(organisationVatUpdate.getCountryCode() == null || organisationVatUpdate.getCountryCode().isEmpty() ? null : organisationVatUpdate.getCountryCode());
        organisationVatEntity.setActive(organisationVatUpdate.getActive());

        return OrganisationVatView.convertFromEntity(organisationVatRepository.save(organisationVatEntity));
    }

    @Transactional
    public Either<Problem, List<OrganisationVatView>> insertVatCodesCsv(String organisationId, MultipartFile file) {
        return csvParser.parseCsv(file, OrganisationVatUpdate.class).fold(
                Either::left,
                organisationVatUpdates -> Either.right(organisationVatUpdates.stream().map(organisationVatUpdate -> insert(organisationId, organisationVatUpdate)).toList())
        );
    }

}
