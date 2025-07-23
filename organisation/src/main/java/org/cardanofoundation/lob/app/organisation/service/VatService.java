package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;
import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.repository.VatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.Constants;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VatService {

    private final VatRepository vatRepository;
    private final CsvParser<VatUpdate> csvParser;
    private final Validator validator;

    public Optional<Vat> findByOrganisationAndCode(String organisationId, String customerCode) {
        return vatRepository.findByIdAndActive(new Vat.Id(organisationId, customerCode),true);
    }

    public List<VatView> findAllByOrganisationId(String organisationId) {
        return vatRepository.findAllByOrganisationId(organisationId).stream()
                .map(VatView::convertFromEntity)
                .toList();
    }

    @Transactional
    public VatView insert(String organisationId, VatUpdate vatUpdate, boolean isUpsert) {

        Optional<Vat> foundEntity = vatRepository.findById(new Vat.Id(organisationId, vatUpdate.getCustomerCode()));
        Vat vatEntity = new Vat();
        vatEntity.setId(new Vat.Id(organisationId, vatUpdate.getCustomerCode()));
        if (foundEntity.isPresent()) {
            if(isUpsert) {
                vatEntity = foundEntity.get();
            } else {
                return VatView.createFail(vatUpdate, Problem.builder()
                        .withTitle("ORGANISATION_VAT_ALREADY_EXISTS")
                        .withDetail("The organisation vat with code :%s already exists".formatted(vatUpdate.getCustomerCode()))
                        .withStatus(Status.CONFLICT)
                        .build());
            }
        }

        if (vatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(vatUpdate.getCountryCode())) {
            return VatView.createFail(vatUpdate, Problem.builder()
                    .withTitle("COUNTRY_CODE_NOT_FOUND")
                    .withDetail("The organisation vat country_code with code %s do not exists".formatted(vatUpdate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }
        vatEntity.setRate(vatUpdate.getRate());
        vatEntity.setDescription(vatUpdate.getDescription());
        vatEntity.setCountryCode(vatUpdate.getCountryCode() == null || vatUpdate.getCountryCode().isEmpty() ? null : vatUpdate.getCountryCode());
        vatEntity.setActive(Optional.ofNullable(vatUpdate.getActive()).orElse(true));
        return VatView.convertFromEntity(vatRepository.save(vatEntity));
    }

    @Transactional
    public VatView update(String organisationId, VatUpdate vatUpdate) {

        Optional<Vat> organisationVat = vatRepository.findById(new Vat.Id(organisationId, vatUpdate.getCustomerCode()));

        if (organisationVat.isEmpty()) {
            return VatView.createFail(vatUpdate, Problem.builder()
                    .withTitle("ORGANISATION_VAT_DO_NOT_EXISTS")
                    .withDetail("The organisation vat with code %s do not exists".formatted(vatUpdate.getCustomerCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        if (vatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(vatUpdate.getCountryCode())) {
            return VatView.createFail(vatUpdate, Problem.builder()
                    .withTitle("COUNTRY_CODE_NOT_FOUND")
                    .withDetail("The organisation vat country_code with code %s do not exists".formatted(vatUpdate.getCountryCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        Vat vatEntity = organisationVat.get();

        vatEntity.setRate(vatUpdate.getRate());
        vatEntity.setDescription(vatUpdate.getDescription());
        vatEntity.setCountryCode(vatUpdate.getCountryCode() == null || vatUpdate.getCountryCode().isEmpty() ? null : vatUpdate.getCountryCode());
        vatEntity.setActive(vatUpdate.getActive());

        return VatView.convertFromEntity(vatRepository.save(vatEntity));
    }

    @Transactional
    public Either<Problem, List<VatView>> insertVatCodesCsv(String organisationId, MultipartFile file) {
        return csvParser.parseCsv(file, VatUpdate.class).fold(
                Either::left,
                organisationVatUpdates -> Either.right(organisationVatUpdates.stream().map(vatUpdate -> {
                    Errors errors = validator.validateObject(vatUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        return VatView.createFail(vatUpdate,Problem.builder()
                                .withTitle(Constants.VALIDATION_ERROR)
                                .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                                .withStatus(Status.BAD_REQUEST)
                                .build());
                    }
                    return insert(organisationId, vatUpdate, true);
                }).toList())
        );
    }

}
