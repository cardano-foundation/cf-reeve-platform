package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.VAT_MAPPINGS;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVWriter;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;
import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.repository.VatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VatService {

    private final VatRepository vatRepository;
    private final CsvParser<VatUpdate> csvParser;
    private final Validator validator;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    public Optional<Vat> findByOrganisationAndCode(String organisationId, String customerCode) {
        return vatRepository.findByIdAndActive(new Vat.Id(organisationId, customerCode),true);
    }

    public Either<ProblemDetail, List<VatView>> findAllByOrganisationId(String organisationId, String customerCode, Double minRate, Double maxRate, String description, List<String> countryCodes, Boolean active, Pageable pageable) {
        Either<ProblemDetail, Pageable> pageables = jpaSortFieldValidator.validateEntity(Vat.class, pageable, VAT_MAPPINGS);
        if(pageables.isLeft()) {
            return Either.left(pageables.getLeft());
        }
        pageable = pageables.get();
        if(countryCodes != null) {
            // Lower case to avoid case sensitivity issues
            countryCodes = countryCodes.stream().filter(s -> s != null && !s.isEmpty()).map(String::toLowerCase).collect(Collectors.toList());
        }
        return Either.right(vatRepository.findAllByOrganisationId(organisationId,customerCode, minRate, maxRate, description, countryCodes, active, pageable).stream()
                .map(VatView::convertFromEntity)
                .toList());
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
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The organisation vat with code :%s already exists".formatted(vatUpdate.getCustomerCode()));
                problem.setTitle(ErrorTitleConstants.ORGANISATION_VAT_ALREADY_EXISTS);
                return VatView.createFail(vatUpdate, problem);
            }
        }

        if (vatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(vatUpdate.getCountryCode())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The organisation vat country_code with code %s do not exists".formatted(vatUpdate.getCountryCode()));
            problem.setTitle(ErrorTitleConstants.COUNTRY_CODE_NOT_FOUND);
            return VatView.createFail(vatUpdate, problem);
        }
        if (vatUpdate.getRate().doubleValue() < 0) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The organisation vat rate cannot be negative");
            problem.setTitle(ErrorTitleConstants.VAT_RATE_CANNOT_BE_NEGATIVE);
            return VatView.createFail(vatUpdate, problem);
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
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The organisation vat with code %s do not exists".formatted(vatUpdate.getCustomerCode()));
            problem.setTitle(ErrorTitleConstants.ORGANISATION_VAT_DO_NOT_EXISTS);
            return VatView.createFail(vatUpdate, problem);
        }

        if (vatUpdate.getCountryCode() != null && !Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).contains(vatUpdate.getCountryCode())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The organisation vat country_code with code %s do not exists".formatted(vatUpdate.getCountryCode()));
            problem.setTitle(ErrorTitleConstants.COUNTRY_CODE_NOT_FOUND);
            return VatView.createFail(vatUpdate, problem);
        }

        if (vatUpdate.getRate().doubleValue() < 0) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The organisation vat rate cannot be negative");
            problem.setTitle(ErrorTitleConstants.VAT_RATE_CANNOT_BE_NEGATIVE);
            return VatView.createFail(vatUpdate, problem);
        }

        Vat vatEntity = organisationVat.get();

        vatEntity.setRate(vatUpdate.getRate());
        vatEntity.setDescription(vatUpdate.getDescription());
        vatEntity.setCountryCode(vatUpdate.getCountryCode() == null || vatUpdate.getCountryCode().isEmpty() ? null : vatUpdate.getCountryCode());
        vatEntity.setActive(vatUpdate.getActive());

        return VatView.convertFromEntity(vatRepository.save(vatEntity));
    }

    @Transactional
    public Either<List<ProblemDetail>, List<VatView>> insertVatCodesCsv(String organisationId, MultipartFile file) {
        return csvParser.parseCsv(file, VatUpdate.class).fold(
                problemDetail -> Either.left(List.of(problemDetail)),
                organisationVatUpdates -> Either.right(organisationVatUpdates.stream().map(vatUpdate -> {
                    Errors errors = validator.validateObject(vatUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        problem.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return VatView.createFail(vatUpdate, problem);
                    }
                    return insert(organisationId, vatUpdate, true);
                }).toList())
        );
    }

    public void downloadCsv(String orgId, String customerCode, Double minRate, Double maxRate, String description, List<String> countryCodes, Boolean active, OutputStream outputStream) {
        Page<Vat> vatCodes = vatRepository.findAllByOrganisationId(orgId, customerCode, minRate, maxRate, description, countryCodes, active, Pageable.unpaged());
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Code", "Rate", "Description", "Country", "Active"};
            csvWriter.writeNext(header, false);
            for (Vat vat: vatCodes) {
                String[] data = {
                        vat.getId().getCustomerCode(),
                        vat.getRate().stripTrailingZeros().toPlainString(),
                        vat.getDescription(),
                        vat.getCountryCode(),
                        String.valueOf(vat.getActive())
                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing VAT codes to CSV", e);
        }
    }
}
