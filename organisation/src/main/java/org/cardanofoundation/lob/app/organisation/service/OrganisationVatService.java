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
import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.repository.VatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganisationVatService {

    private final VatRepository vatRepository;
    private final CsvParser<VatUpdate> csvParser;

    public Optional<OrganisationVat> findByOrganisationAndCode(String organisationId, String customerCode) {
        return vatRepository.findById(new OrganisationVat.Id(organisationId, customerCode));
    }

    public List<VatView> findAllByOrganisationId(String organisationId) {
        return vatRepository.findAllByOrganisationId(organisationId).stream()
                .map(VatView::convertFromEntity)
                .toList();
    }

    @Transactional
    public VatView insert(String organisationId, VatUpdate vatUpdate) {

        Optional<OrganisationVat> organisationVat = vatRepository.findById(new OrganisationVat.Id(organisationId, vatUpdate.getCustomerCode()));

        if (organisationVat.isPresent()) {
            return VatView.createFail(vatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("ORGANISATION_VAT_ALREADY_EXISTS")
                    .withDetail("The organisation vat with code :%s already exists".formatted(vatUpdate.getCustomerCode()))
                    .withStatus(Status.CONFLICT)
                    .build());
        }

        if (vatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(vatUpdate.getCountryCode())) {
            return VatView.createFail(vatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("COUNTRY_CODE_NOT_FOUND")
                    .withDetail("The organisation vat country_code with code %s do not exists".formatted(vatUpdate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        OrganisationVat organisationVatEntity = OrganisationVat.builder()
                .id(new OrganisationVat.Id(organisationId, vatUpdate.getCustomerCode()))
                .rate(vatUpdate.getRate())
                .description(vatUpdate.getDescription())
                .countryCode(vatUpdate.getCountryCode() == null || vatUpdate.getCountryCode().isEmpty() ? null : vatUpdate.getCountryCode())
                .active(vatUpdate.getActive())
                .build();

        return VatView.convertFromEntity(vatRepository.save(organisationVatEntity));
    }

    @Transactional
    public VatView update(String organisationId, VatUpdate vatUpdate) {

        Optional<OrganisationVat> organisationVat = vatRepository.findById(new OrganisationVat.Id(organisationId, vatUpdate.getCustomerCode()));

        if (organisationVat.isEmpty()) {
            return VatView.createFail(vatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("ORGANISATION_VAT_DO_NOT_EXISTS")
                    .withDetail("The organisation vat with code %s do not exists".formatted(vatUpdate.getCustomerCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        if (vatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(vatUpdate.getCountryCode())) {
            return VatView.createFail(vatUpdate.getCustomerCode(), Problem.builder()
                    .withTitle("COUNTRY_CODE_NOT_FOUND")
                    .withDetail("The organisation vat country_code with code %s do not exists".formatted(vatUpdate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        OrganisationVat organisationVatEntity = organisationVat.get();

        organisationVatEntity.setRate(vatUpdate.getRate());
        organisationVatEntity.setDescription(vatUpdate.getDescription());
        organisationVatEntity.setCountryCode(vatUpdate.getCountryCode() == null || vatUpdate.getCountryCode().isEmpty() ? null : vatUpdate.getCountryCode());
        organisationVatEntity.setActive(vatUpdate.getActive());

        return VatView.convertFromEntity(vatRepository.save(organisationVatEntity));
    }

    @Transactional
    public Either<Problem, List<VatView>> insertVatCodesCsv(String organisationId, MultipartFile file) {
        return csvParser.parseCsv(file, VatUpdate.class).fold(
                Either::left,
                organisationVatUpdates -> Either.right(organisationVatUpdates.stream().map(vatUpdate -> insert(organisationId, vatUpdate)).toList())
        );
    }

}
