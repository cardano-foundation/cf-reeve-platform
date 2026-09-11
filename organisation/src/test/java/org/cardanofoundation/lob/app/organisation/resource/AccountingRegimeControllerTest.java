package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.request.AccountingRegimeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountingRegimeView;
import org.cardanofoundation.lob.app.organisation.service.AccountingRegimeService;

@ExtendWith(MockitoExtension.class)
class AccountingRegimeControllerTest {

    @Mock
    private AccountingRegimeService accountingRegimeService;

    @InjectMocks
    private AccountingRegimeController accountingRegimeController;

    @Test
    void getAllAccountingRegimes_success() {
        AccountingRegimeView view = AccountingRegimeView.createSuccess("IFRS", "IFRS Label", true);
        when(accountingRegimeService.getAllAccountingRegimes("org123")).thenReturn(List.of(view));

        ResponseEntity<List<AccountingRegimeView>> response = accountingRegimeController.getAllAccountingRegimes("org123");

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("IFRS", response.getBody().get(0).getCode());
    }

    @Test
    void insertAccountingRegime_success() {
        AccountingRegimeUpdate update = mock(AccountingRegimeUpdate.class);
        AccountingRegimeView view = AccountingRegimeView.createSuccess("IFRS", "IFRS Label", true);
        when(accountingRegimeService.insertAccountingRegime("org123", update, false)).thenReturn(view);

        ResponseEntity<AccountingRegimeView> response = accountingRegimeController.insertAccountingRegime("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals("IFRS", response.getBody().getCode());
    }

    @Test
    void insertAccountingRegime_error() {
        AccountingRegimeUpdate update = mock(AccountingRegimeUpdate.class);
        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Accounting regime with code IFRS already exists");
        AccountingRegimeView view = AccountingRegimeView.createFail(error, new AccountingRegimeUpdate("IFRS", "IFRS Label", true));
        when(accountingRegimeService.insertAccountingRegime("org123", update, false)).thenReturn(view);

        ResponseEntity<AccountingRegimeView> response = accountingRegimeController.insertAccountingRegime("org123", update);

        assertEquals(HttpStatus.CONFLICT.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getError().isPresent());
    }

    @Test
    void updateAccountingRegime_success() {
        AccountingRegimeUpdate update = mock(AccountingRegimeUpdate.class);
        AccountingRegimeView view = AccountingRegimeView.createSuccess("IFRS", "Updated Label", false);
        when(accountingRegimeService.updateAccountingRegime("org123", update)).thenReturn(view);

        ResponseEntity<AccountingRegimeView> response = accountingRegimeController.updateAccountingRegime("org123", update);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals("Updated Label", response.getBody().getLabel());
    }

    @Test
    void updateAccountingRegime_error() {
        AccountingRegimeUpdate update = mock(AccountingRegimeUpdate.class);
        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Accounting regime with code IFRS not found");
        AccountingRegimeView view = AccountingRegimeView.createFail(error, new AccountingRegimeUpdate("IFRS", "IFRS Label", true));
        when(accountingRegimeService.updateAccountingRegime("org123", update)).thenReturn(view);

        ResponseEntity<AccountingRegimeView> response = accountingRegimeController.updateAccountingRegime("org123", update);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getError().isPresent());
    }

    @Test
    void insertAccountingRegimesCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        List<AccountingRegimeView> views = List.of(AccountingRegimeView.createSuccess("IFRS", "IFRS Label", true));
        when(accountingRegimeService.insertViaCsv("org123", file)).thenReturn(Either.right(views));

        ResponseEntity<Object> response = accountingRegimeController.insertAccountingRegimesCsv("org123", file);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals(views, response.getBody());
    }

    @Test
    void insertAccountingRegimesCsv_problem() {
        MultipartFile file = mock(MultipartFile.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV parse error");
        when(accountingRegimeService.insertViaCsv("org123", file)).thenReturn(Either.left(problem));

        ResponseEntity<Object> response = accountingRegimeController.insertAccountingRegimesCsv("org123", file);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertEquals(problem, response.getBody());
    }

}
