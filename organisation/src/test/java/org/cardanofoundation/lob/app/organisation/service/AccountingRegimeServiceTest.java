package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountingRegime;
import org.cardanofoundation.lob.app.organisation.domain.request.AccountingRegimeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountingRegimeView;
import org.cardanofoundation.lob.app.organisation.repository.AccountingRegimeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class AccountingRegimeServiceTest {

    @Mock
    private AccountingRegimeRepository accountingRegimeRepository;
    @Mock
    private CsvParser<AccountingRegimeUpdate> csvParser;
    @Mock
    private Validator validator;

    @InjectMocks
    private AccountingRegimeService accountingRegimeService;

    private final String organisationId = "org123";

    @Test
    void getAllAccountingRegimes_success() {
        AccountingRegime regime = new AccountingRegime(new AccountingRegime.Id(organisationId, "IFRS"), "IFRS Label", true);
        when(accountingRegimeRepository.findAllByOrganisationId(organisationId)).thenReturn(Set.of(regime));

        List<AccountingRegimeView> result = accountingRegimeService.getAllAccountingRegimes(organisationId);

        assertEquals(1, result.size());
        assertEquals("IFRS", result.get(0).getCode());
        assertEquals("IFRS Label", result.get(0).getLabel());
        assertTrue(result.get(0).isActive());
    }

    @Test
    void getAllAccountingRegimes_empty() {
        when(accountingRegimeRepository.findAllByOrganisationId(organisationId)).thenReturn(Set.of());

        List<AccountingRegimeView> result = accountingRegimeService.getAllAccountingRegimes(organisationId);

        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByOrganisationId_delegatesToRepository() {
        AccountingRegime regime = new AccountingRegime(new AccountingRegime.Id(organisationId, "IFRS"), "IFRS Label", true);
        when(accountingRegimeRepository.findAllByOrganisationId(organisationId)).thenReturn(Set.of(regime));

        Set<AccountingRegime> result = accountingRegimeService.findAllByOrganisationId(organisationId);

        assertEquals(Set.of(regime), result);
    }

    @Test
    void getAccountingRegime_found() {
        AccountingRegime.Id id = new AccountingRegime.Id(organisationId, "IFRS");
        AccountingRegime regime = new AccountingRegime(id, "IFRS Label", true);
        when(accountingRegimeRepository.findById(id)).thenReturn(Optional.of(regime));

        Optional<AccountingRegimeView> result = accountingRegimeService.getAccountingRegime(organisationId, "IFRS");

        assertTrue(result.isPresent());
        assertEquals("IFRS", result.get().getCode());
    }

    @Test
    void getAccountingRegime_notFound() {
        AccountingRegime.Id id = new AccountingRegime.Id(organisationId, "IFRS");
        when(accountingRegimeRepository.findById(id)).thenReturn(Optional.empty());

        Optional<AccountingRegimeView> result = accountingRegimeService.getAccountingRegime(organisationId, "IFRS");

        assertFalse(result.isPresent());
    }

    @Test
    void updateAccountingRegime_notFound() {
        AccountingRegimeUpdate update = new AccountingRegimeUpdate("IFRS", "New Label", false);
        when(accountingRegimeRepository.findById(new AccountingRegime.Id(organisationId, "IFRS"))).thenReturn(Optional.empty());

        AccountingRegimeView result = accountingRegimeService.updateAccountingRegime(organisationId, update);

        assertEquals("IFRS", result.getCode());
        assertTrue(result.getError().isPresent());
        assertEquals("ACCOUNTING_REGIME_NOT_FOUND", result.getError().get().getTitle());
        verify(accountingRegimeRepository, never()).save(any());
    }

    @Test
    void updateAccountingRegime_success() {
        AccountingRegimeUpdate update = new AccountingRegimeUpdate("IFRS", "New Label", false);
        AccountingRegime existing = new AccountingRegime(new AccountingRegime.Id(organisationId, "IFRS"), "Old Label", true);
        when(accountingRegimeRepository.findById(new AccountingRegime.Id(organisationId, "IFRS"))).thenReturn(Optional.of(existing));
        when(accountingRegimeRepository.save(existing)).thenReturn(existing);

        AccountingRegimeView result = accountingRegimeService.updateAccountingRegime(organisationId, update);

        assertTrue(result.getError().isEmpty());
        assertEquals("New Label", result.getLabel());
        assertFalse(result.isActive());
        assertEquals("New Label", existing.getLabel());
        assertFalse(existing.isActive());
    }

    @Test
    void insertAccountingRegime_alreadyExists_noUpsert() {
        AccountingRegimeUpdate update = new AccountingRegimeUpdate("IFRS", "IFRS Label", true);
        AccountingRegime existing = new AccountingRegime(new AccountingRegime.Id(organisationId, "IFRS"), "IFRS Label", true);
        when(accountingRegimeRepository.findById(new AccountingRegime.Id(organisationId, "IFRS"))).thenReturn(Optional.of(existing));

        AccountingRegimeView result = accountingRegimeService.insertAccountingRegime(organisationId, update, false);

        assertTrue(result.getError().isPresent());
        assertEquals("ACCOUNTING_REGIME_ALREADY_EXISTS", result.getError().get().getTitle());
        verify(accountingRegimeRepository, never()).save(any());
    }

    @Test
    void insertAccountingRegime_upsert_existing() {
        AccountingRegimeUpdate update = new AccountingRegimeUpdate("IFRS", "Updated Label", false);
        AccountingRegime existing = new AccountingRegime(new AccountingRegime.Id(organisationId, "IFRS"), "Old Label", true);
        when(accountingRegimeRepository.findById(new AccountingRegime.Id(organisationId, "IFRS"))).thenReturn(Optional.of(existing));
        when(accountingRegimeRepository.save(existing)).thenReturn(existing);

        AccountingRegimeView result = accountingRegimeService.insertAccountingRegime(organisationId, update, true);

        assertTrue(result.getError().isEmpty());
        assertEquals("Updated Label", result.getLabel());
        assertFalse(result.isActive());
    }

    @Test
    void insertAccountingRegime_success_new() {
        AccountingRegimeUpdate update = new AccountingRegimeUpdate("IFRS", "IFRS Label", true);
        when(accountingRegimeRepository.findById(new AccountingRegime.Id(organisationId, "IFRS"))).thenReturn(Optional.empty());

        ArgumentCaptor<AccountingRegime> captor = ArgumentCaptor.forClass(AccountingRegime.class);
        when(accountingRegimeRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        AccountingRegimeView result = accountingRegimeService.insertAccountingRegime(organisationId, update, false);

        assertTrue(result.getError().isEmpty());
        assertEquals("IFRS", result.getCode());
        assertEquals("IFRS Label", result.getLabel());
        assertEquals("IFRS", captor.getValue().getId().getCode());
        assertEquals(organisationId, captor.getValue().getId().getOrganisationId());
    }

    @Test
    void insertViaCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parse Error");
        when(csvParser.parseCsv(file, AccountingRegimeUpdate.class)).thenReturn(Either.left(problem));

        Either<ProblemDetail, List<AccountingRegimeView>> result = accountingRegimeService.insertViaCsv(organisationId, file);

        assertTrue(result.isLeft());
        assertEquals("Parse Error", result.getLeft().getDetail());
    }

    @Test
    void insertViaCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);
        AccountingRegimeUpdate update = new AccountingRegimeUpdate(null, "IFRS Label", true);
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(objectError.getDefaultMessage()).thenReturn("Code is required");
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(update)).thenReturn(errors);
        when(csvParser.parseCsv(file, AccountingRegimeUpdate.class)).thenReturn(Either.right(List.of(update)));

        Either<ProblemDetail, List<AccountingRegimeView>> result = accountingRegimeService.insertViaCsv(organisationId, file);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertTrue(result.get().get(0).getError().isPresent());
        assertEquals("VALIDATION_ERROR", result.get().get(0).getError().get().getTitle());
        assertEquals("Code is required", result.get().get(0).getError().get().getDetail());
        verify(accountingRegimeRepository, never()).save(any());
    }

    @Test
    void insertViaCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        AccountingRegimeUpdate update = new AccountingRegimeUpdate("IFRS", "IFRS Label", true);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(update)).thenReturn(errors);
        when(csvParser.parseCsv(file, AccountingRegimeUpdate.class)).thenReturn(Either.right(List.of(update)));
        when(accountingRegimeRepository.findById(new AccountingRegime.Id(organisationId, "IFRS"))).thenReturn(Optional.empty());
        when(accountingRegimeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Either<ProblemDetail, List<AccountingRegimeView>> result = accountingRegimeService.insertViaCsv(organisationId, file);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertTrue(result.get().get(0).getError().isEmpty());
        assertEquals("IFRS", result.get().get(0).getCode());
    }

}
