package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.CURRENCY_MAPPINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
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

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.CurrencyRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

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
    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;

    @InjectMocks
    private CurrencyService currencyService;

    private final String organisationId = "org123";
    private final String customerCode = "cust001";
    private Currency.Id currencyId;
    private Currency currency;

    @BeforeEach
    void setUp() {
        currencyId = new Currency.Id(organisationId, customerCode);
        currency = new Currency(currencyId, "USD", true);

    }

    @Test
    void getAllCurrencies() {
        when(currencyRepository.findAllByOrganisationId("org123", null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(
                new Currency(new Currency.Id("org123", "USD"), "USD", true)
        )));
        when(jpaSortFieldValidator.validateEntity(Currency.class, Pageable.unpaged(), CURRENCY_MAPPINGS)).thenReturn(Either.right(Pageable.unpaged()));
        Either<Problem, List<CurrencyView>> currencies = currencyService.getAllCurrencies("org123", null, null, Pageable.unpaged());
        assertTrue(currencies.isRight());
        assertEquals(1, currencies.get().size());
        assertEquals("USD", currencies.get().getFirst().getIsoCode());
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
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));
        when(currencyRepository.save(any(Currency.class)))
                .thenReturn(new Currency(new Currency.Id("org123", "USD"), "USD123", true));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verify(currencyRepository).save(any(Currency.class));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertEquals("USD", response.getCode());
        assertEquals("USD123", response.getIsoCode());
    }

    @Test
    void updateCurrency_cannotSetInactive() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));

        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);
        when(chartOfAccountRepository.findTopByCurrencyIdAndIdOrganisationId(eq("USD"), eq("org123"))).thenReturn(Optional.of(chartOfAccount));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", false);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertTrue(response.getError().isPresent());
        assertEquals("USD", response.getCode());
        assertEquals("USD123", response.getIsoCode());
    }

    @Test
    void updateCurrency_updateInactiveSuccess() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));
        when(currencyRepository.save(any(Currency.class)))
                .thenReturn(new Currency(new Currency.Id("org123", "USD"), "USD123", true));
        when(chartOfAccountRepository.findTopByCurrencyIdAndIdOrganisationId(eq("USD"), eq("org123"))).thenReturn(Optional.empty());

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", false);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertFalse(response.getError().isPresent());
        assertEquals("USD", response.getCode());
        assertEquals("USD123", response.getIsoCode());
    }

    @Test
    void insertCurrency_alreadyExists() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
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
                .thenReturn(new Currency(new Currency.Id("org123", "USD"), "USD123", true));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate, false);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verify(currencyRepository).save(any(Currency.class));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertEquals("USD", response.getCode());
        assertEquals("USD123", response.getIsoCode());
    }

    @Test
    void upsertCurrency_success() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));
        when(currencyRepository.save(any(Currency.class)))
                .thenReturn(new Currency(new Currency.Id("org123", "USD"), "USD123", true));
        when(chartOfAccountRepository.findTopByCurrencyIdAndIdOrganisationId(eq("USD"), eq("org123"))).thenReturn(Optional.empty());

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", false);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate, true);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertFalse(response.getError().isPresent());
        assertEquals("USD", response.getCode());
        assertEquals("USD123", response.getIsoCode());
    }

    @Test
    void upsertCurrency_cantUpdateInactive() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));
        ChartOfAccount chartOfAccount = mock(ChartOfAccount.class);
        when(chartOfAccountRepository.findTopByCurrencyIdAndIdOrganisationId(eq("USD"), eq("org123"))).thenReturn(Optional.of(chartOfAccount));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", false);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate, true);

        verify(currencyRepository).findById(new Currency.Id("org123", "USD"));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertTrue(response.getError().isPresent());
        assertEquals("USD", response.getCode());
        assertEquals("USD123", response.getIsoCode());
    }

    @Test
    void getCurrency() {
        Currency existingCurrency = new Currency(new Currency.Id("org123", "USD"), "USD", true);
        when(currencyRepository.findById(new Currency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));

        Optional<CurrencyView> response = currencyService.getCurrency("org123", "USD");

        assertNotNull(response);
        assertEquals("USD", response.get().getCode());
        assertEquals("USD", response.get().getIsoCode());
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

        Currency savedCurrency = new Currency(new Currency.Id("org123", "USD"), "USD123", true);
        when(currencyRepository.save(any(Currency.class))).thenReturn(savedCurrency);

        Either<Problem, List<CurrencyView>> response = currencyService.insertViaCsv("org123", file);

        assertNotNull(response);
        assertTrue(response.isRight());
        assertEquals(1, response.get().size());
        assertEquals("USD", response.get().getFirst().getCode());
        assertEquals("USD123", response.get().getFirst().getIsoCode());
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

    @Test
    void shouldWriteCurrenciesToCsv() throws Exception {
        // given
        String orgId = "org-1";
        String code = "EUR";
        List<String> isoCodes = List.of("EUR", "USD");

        Currency eur = new Currency(new Currency.Id(orgId,"EUR"), "EUR", true);
        Currency usd = new Currency(new Currency.Id(orgId,"USD"), "USD", false);

        Page<Currency> page = new PageImpl<>(List.of(eur, usd));

        when(currencyRepository.findAllByOrganisationId(
                eq(orgId),
                eq(code),
                eq(isoCodes),
                eq(Pageable.unpaged())
        )).thenReturn(page);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // when
        currencyService.downloadCsv(orgId, code, isoCodes, outputStream);

        // then
        String csv = outputStream.toString(StandardCharsets.UTF_8);

        String[] lines = csv.split("\\R");

        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("Code,ISO Code,Active");
        assertThat(lines[1]).isEqualTo("EUR,EUR,true");
        assertThat(lines[2]).isEqualTo("USD,USD,false");

        verify(currencyRepository).findAllByOrganisationId(
                orgId, code, isoCodes, Pageable.unpaged()
        );
    }
}
