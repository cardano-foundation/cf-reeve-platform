package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCurrency;
import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationCurrencyRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class CurrencyServiceTest {

    @Mock
    private OrganisationCurrencyRepository currencyRepository;
    @Mock
    private CsvParser<CurrencyUpdate> csvParser;

    @InjectMocks
    private CurrencyService currencyService;

    @Test
    void getAllCurrencies() {
        when(currencyRepository.findAllByOrganisationId("org123")).thenReturn(Set.of(
                new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD")
        ));

        List<CurrencyView> currencies = currencyService.getAllCurrencies("org123");

        assertEquals(1, currencies.size());
        assertEquals("USD", currencies.getFirst().getCurrencyId());
    }

    @Test
    void updateCurrency_notFound() {
        when(currencyRepository.findById(new OrganisationCurrency.Id("org123", "USD"))).thenReturn(Optional.empty());

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        assertNotNull(response.getError());
        assertEquals("Currency with customer code USD not found", response.getError().get().getDetail());
    }

    @Test
    void updateCurrency_success() {
        OrganisationCurrency existingCurrency = new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD");
        when(currencyRepository.findById(new OrganisationCurrency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));
        when(currencyRepository.save(any(OrganisationCurrency.class)))
                .thenReturn(new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD123"));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.updateCurrency("org123", currencyUpdate);

        verify(currencyRepository).findById(new OrganisationCurrency.Id("org123", "USD"));
        verify(currencyRepository).save(any(OrganisationCurrency.class));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertEquals("USD", response.getCustomerCode());
        assertEquals("USD123", response.getCurrencyId());
    }

    @Test
    void insertCurrency_alreadyExists() {
        OrganisationCurrency existingCurrency = new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD");
        when(currencyRepository.findById(new OrganisationCurrency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate);

        assertNotNull(response.getError());
        assertEquals("Currency with customer code USD already exists", response.getError().get().getDetail());
    }

    @Test
    void insertCurrency_success() {
        when(currencyRepository.findById(new OrganisationCurrency.Id("org123", "USD"))).thenReturn(Optional.empty());
        when(currencyRepository.save(any(OrganisationCurrency.class)))
                .thenReturn(new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD123"));

        CurrencyUpdate currencyUpdate = new CurrencyUpdate("USD", "USD123", true);

        CurrencyView response = currencyService.insertCurrency("org123", currencyUpdate);

        verify(currencyRepository).findById(new OrganisationCurrency.Id("org123", "USD"));
        verify(currencyRepository).save(any(OrganisationCurrency.class));
        verifyNoMoreInteractions(currencyRepository);

        assertNotNull(response);
        assertEquals("USD", response.getCustomerCode());
        assertEquals("USD123", response.getCurrencyId());
    }

    @Test
    void getCurrency() {
        OrganisationCurrency existingCurrency = new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD");
        when(currencyRepository.findById(new OrganisationCurrency.Id("org123", "USD"))).thenReturn(Optional.of(existingCurrency));

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
        when(csvParser.parseCsv(file, CurrencyUpdate.class)).thenReturn(Either.right(List.of(currencyUpdate)));

        OrganisationCurrency savedCurrency = new OrganisationCurrency(new OrganisationCurrency.Id("org123", "USD"), "USD123");
        when(currencyRepository.save(any(OrganisationCurrency.class))).thenReturn(savedCurrency);

        Either<Problem, List<CurrencyView>> response = currencyService.insertViaCsv("org123", file);

        assertNotNull(response);
        assertTrue(response.isRight());
        assertEquals(1, response.get().size());
        assertEquals("USD", response.get().getFirst().getCustomerCode());
        assertEquals("USD123", response.get().getFirst().getCurrencyId());
    }
}
