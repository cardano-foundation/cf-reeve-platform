package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.repository.CurrencyRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@RequiredArgsConstructor
@Slf4j
@Service
public class CurrencyService {

    private final CurrencyRepository currencyRepository;
    private final CsvParser<CurrencyUpdate> csvParser;

    public List<CurrencyView> getAllCurrencies(String orgId) {
        return currencyRepository.findAllByOrganisationId(orgId)
                .stream()
                .map(currency -> CurrencyView.createSuccess(currency.getId().getCustomerCode(), currency.getCurrencyId()))
                .toList();
    }

    public Optional<Currency> findByOrganisationIdAndCode(@Param("organisationId") String organisationId,
                                                          @Param("customerCode") String customerCode) {
        return currencyRepository.findById(new Currency.Id(organisationId, customerCode));
    }

    public Set<Currency> findAllByOrganisationId(@Param("organisationId") String organisationId ){
        return currencyRepository.findAllByOrganisationId(organisationId);
    }

    public CurrencyView updateCurrency(String orgId, @Valid CurrencyUpdate currencyUpdate) {
        return currencyRepository.findById(new Currency.Id(orgId, currencyUpdate.getCustomerCode()))
                .map(currency -> {
                    currency.setCurrencyId(currencyUpdate.getCurrencyId());
                    Currency updatedEntity = currencyRepository.save(currency);
                    return CurrencyView.createSuccess(updatedEntity.getId().getCustomerCode(), updatedEntity.getCurrencyId());
                })
                .orElseGet(() -> {
                    Problem error = Problem.builder()
                            .withStatus(Status.NOT_FOUND)
                            .withTitle("Currency not found")
                            .withDetail("Currency with customer code " + currencyUpdate.getCustomerCode() + " not found")
                            .build();
                    return CurrencyView.createFail(error, currencyUpdate.getCustomerCode());
                });
    }

    public CurrencyView insertCurrency(String orgId, @Valid CurrencyUpdate currencyUpdate) {
        return getCurrency(orgId, currencyUpdate.getCustomerCode())
                .map(currencyView -> {
                    Problem error = Problem.builder()
                            .withStatus(Status.BAD_REQUEST)
                            .withTitle("Currency already exists")
                            .withDetail("Currency with customer code " + currencyView.getCustomerCode() + " already exists")
                            .build();
                    return CurrencyView.createFail(error, currencyUpdate.getCustomerCode());
                })
                .orElseGet(() -> {
                    Currency currency = new Currency(new Currency.Id(orgId, currencyUpdate.getCustomerCode()), currencyUpdate.getCurrencyId());
                    Currency save = currencyRepository.save(currency);
                    return CurrencyView.createSuccess(save.getId().getCustomerCode(), save.getCurrencyId());
                });
    }

    public Optional<CurrencyView> getCurrency(String orgId, String customerCode) {
        return currencyRepository.findById(new Currency.Id(orgId, customerCode))
                .map(currency -> CurrencyView.createSuccess(currency.getId().getCustomerCode(), currency.getCurrencyId()));
    }

    public Either<Problem, List<CurrencyView>> insertViaCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, CurrencyUpdate.class).fold(
                Either::left,
                    currencyUpdates -> Either.right(currencyUpdates.stream().map(currencyUpdate -> insertCurrency(orgId, currencyUpdate)).toList())
        );
    }
}
