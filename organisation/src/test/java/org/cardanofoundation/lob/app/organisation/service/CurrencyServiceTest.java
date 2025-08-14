package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.CURRENCY_MAPPINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.repository.CurrencyRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.JpaSortFieldValidator;

@ExtendWith(MockitoExtension.class)
class CurrencyServiceTest {

    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private CsvParser<CurrencyUpdate> csvParser;
    @Mock
    private Validator validator;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    @InjectMocks
    private CurrencyService currencyService;

    private final String organisationId = "org123";
    private final String customerCode = "cust001";
    private Currency.Id currencyId;
    private Currency currency;

    @BeforeEach
    void setUp() {
        currencyId = new Currency.Id(organisationId, customerCode);
        currency = new Currency(currencyId, "USD");

    }

    @Test
    void getAllCurrencies() {
        when(currencyRepository.findAllByOrganisationId("org123", null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(
                new Currency(new Currency.Id("org123", "USD"), "USD")
        )));
        when(jpaSortFieldValidator.validateEntity(Currency.class, Pageable.unpaged(), CURRENCY_MAPPINGS)).thenReturn(Either.right(Pageable.unpaged()));
        Either<Problem, List<CurrencyView>> currencies = currencyService.getAllCurrencies("org123", null, null, Pageable.unpaged());
        assertTrue(currencies.isRight());
        assertEquals(1, currencies.get().size());
        assertEquals("USD", currencies.get().getFirst().getCurrencyId());
    }

    @Test
    void updateCurrency_notFound() {
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.empty());

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        assertNotNull(response.getError());
        assertEquals("Currency with customer code USD not found", response.getError().get().getDetail());
    }

    @Test
    void updateCurrency_success() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD");
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));
        when(currencyRepository.save(any(Currency.class)))
                .thenReturn(new Currency(new Currency.Id("org123", "USD"), "USD123"));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verify(currencyRepository).save(any(Currency.class));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertEquals("USD", response.getCustomerCode());
        assertEquals("USD123", response.getCurrencyId());
    }

    @Test
    void insertCurrency_alreadyExists() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD");
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate, false);

        assertNotNull(response.getError());
        assertEquals("Currency with customer code USD already exists", response.getError().get().getDetail());
    }

    @Test
    void insertCurrency_success() {
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.empty());
        when(currencyRepository.save(any(Currency.class)))
                .thenReturn(new Currency(new Currency.Id("org123", "USD"), "USD123"));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate, false);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verify(currencyRepository).save(any(Currency.class));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertEquals("USD", response.getCustomerCode());
        assertEquals("USD123", response.getCurrencyId());
    }

    @Test
    void getCurrency() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD");
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));

        Optional<CurrencyView> response = currencyService.getCurrency("org123", "USD");

        assertNotNull(response);
        assertEquals("USD", response.get().getCustomerCode());
        assertEquals("USD", response.get().getCurrencyId());
    }

    @Test
    void insertViaCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, CurrencyUpdate.class)).thenReturn(Either.left(Problem.valueOf(Status.BAD_REQUEST)));

        Either<Problem, List<CurrencyView>> response = currencyService.insertViaCsv("org123", file);

        assertNotNull(response);
        assertTrue(response.isLeft());
        assertEquals(Status.BAD_REQUEST, response.getLeft().getStatus());
    }

    @Test
    void insertViaCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(currencyUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        when(csvParser.parseCsv(file, CurrencyUpdate.class)).thenReturn(Either.right(List.of(currencyUpdate)));

        Currency savedCurrency = new Currency(new Currency.Id("org123", "USD"), "USD123");
        when(currencyRepository.save(any(Currency.class))).thenReturn(savedCurrency);

        Either<Problem, List<CurrencyView>> response = currencyService.insertViaCsv("org123", file);

        assertNotNull(response);
        assertTrue(response.isRight());
        assertEquals(1, response.get().size());
        assertEquals("USD", response.get().getFirst().getCustomerCode());
        assertEquals("USD123", response.get().getFirst().getCurrencyId());
    }

    @Test
    void insertViaCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);
        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(currencyUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        when(csvParser.parseCsv(file, CurrencyUpdate.class)).thenReturn(Either.right(List.of(currencyUpdate)));


        Either<Problem, List<CurrencyView>> response = currencyService.insertViaCsv("org123", file);

        assertNotNull(response);
        assertTrue(response.isRight());
        assertEquals(1, response.get().size());
        assertNotNull(response.get().getFirst().getError());
        assertEquals("Default Message", response.get().getFirst().getError().get().getDetail());
    }

    @Test
    void testFindByOrganisationIdAndCode_Found() {
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));

        Optional<Currency> result = currencyService.findByOrganisationIdAndCode(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(currency, result.get());
        verify(currencyRepository).findById(currencyId);
    }

    @Test
    void testFindByOrganisationIdAndCode_NotFound() {
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.empty());

        Optional<Currency> result = currencyService.findByOrganisationIdAndCode(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(currencyRepository).findById(currencyId);
    }

    @Test
    void testFindAllByOrganisationId() {
        Set<Currency> currencies = Set.of(currency);
        when(currencyRepository.findAllByOrganisationId(organisationId)).thenReturn(currencies);

        Set<Currency> result = currencyService.findAllByOrganisationId(organisationId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(currency));
        verify(currencyRepository).findAllByOrganisationId(organisationId);
    }
}
