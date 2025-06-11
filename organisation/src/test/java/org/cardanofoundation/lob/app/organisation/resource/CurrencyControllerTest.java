package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.service.CurrencyService;

@ExtendWith(MockitoExtension.class)
class CurrencyControllerTest {

    @Mock
    private CurrencyService currencyService;

    @InjectMocks
    private CurrencyController currencyController;

    @Test
    void getAllCurrencies() {
        CurrencyView view = mock(CurrencyView.class);
        when(currencyService.getAllCurrencies("org123")).thenReturn(List.of(view));

        ResponseEntity<List<CurrencyView>> response = currencyController.getAllCurrencies("org123");
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(view, response.getBody().getFirst());
    }

    @Test
    void getCurrency_notFound() {
        when(currencyService.getCurrency("org123", "USD")).thenReturn(Optional.empty());

        ResponseEntity<CurrencyView> response = currencyController.getCurrency("org123", "USD");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getCurrency_success() {
        CurrencyView view = mock(CurrencyView.class);
        when(currencyService.getCurrency("org123", "USD")).thenReturn(Optional.of(view));

        ResponseEntity<CurrencyView> response = currencyController.getCurrency("org123", "USD");
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(view, response.getBody());
    }

    @Test
    void insertCurrency_success() {
        CurrencyView view = mock(CurrencyView.class);
        CurrencyUpdate update = mock(CurrencyUpdate.class);

        when(currencyService.insertCurrency("org123", update)).thenReturn(view);
        ResponseEntity<CurrencyView> response = currencyController.insertCurrency("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(view, response.getBody());
    }

    @Test
    void updateCurrency_success() {
        CurrencyView view = mock(CurrencyView.class);
        CurrencyUpdate update = mock(CurrencyUpdate.class);

        when(currencyService.updateCurrency("org123", update)).thenReturn(view);
        ResponseEntity<CurrencyView> response = currencyController.updateCurrency("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(view, response.getBody());
    }

    @Test
    void insertCurrenciesCsv_success() {
        CurrencyView view = mock(CurrencyView.class);
        MultipartFile file = mock(MultipartFile.class);

        when(currencyService.insertViaCsv("org123", file)).thenReturn(Either.right(List.of(view)));
        ResponseEntity<?> response = currencyController.insertCurrenciesCsv("org123", file);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(List.of(view), response.getBody());
    }

    @Test
    void insertCurrenciesCsv_failure() {
        MultipartFile file = mock(MultipartFile.class);
        Either<Problem, List<CurrencyView>> either = Either.left(Problem.builder().withTitle("Error").withStatus(Status.BAD_REQUEST).build());
        when(currencyService.insertViaCsv("org123", file)).thenReturn(either);

        ResponseEntity<?> response = currencyController.insertCurrenciesCsv("org123", file);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(either.getLeft(), response.getBody());
    }
}
