package org.cardanofoundation.lob.app.organisation.service;
import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.ACCOUNT_EVENT_MAPPINGS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.repository.AccountEventRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.JpaSortFieldValidator;

@ExtendWith(MockitoExtension.class)
class AccountEventServiceTest {

    @Mock
    private AccountEventRepository accountEventRepository;

    @Mock
    private ReferenceCodeRepository referenceCodeRepository;

    @Mock
    private OrganisationService organisationService;
    @Mock
    private CsvParser<EventCodeUpdate> csvParser;
    @Mock
    private Validator validator;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    @InjectMocks
    private AccountEventService accountEventService;

    private static final String ORG_ID = "org123";
    private static final String DEBIT_REF_CODE = "debit001";
    private static final String CREDIT_REF_CODE = "credit002";

    private AccountEvent.Id accountEventId;
    private AccountEvent mockAccountEvent;
    private ReferenceCode mockDebitReference;
    private ReferenceCode mockCreditReference;
    private EventCodeUpdate mockEventCodeUpdate;
    private Organisation mockOrganisation;

    @BeforeEach
    void setUp() {
        accountEventId = new AccountEvent.Id(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE);

        mockAccountEvent = AccountEvent.builder()
                .id(accountEventId)
                .customerCode(DEBIT_REF_CODE + CREDIT_REF_CODE)
                .name("Test Event")
                .active(true)
                .build();

        mockDebitReference = ReferenceCode.builder()
                .id(new ReferenceCode.Id(ORG_ID, DEBIT_REF_CODE))
                .name("Debit Reference")
                .build();

        mockCreditReference = ReferenceCode.builder()
                .id(new ReferenceCode.Id(ORG_ID, CREDIT_REF_CODE))
                .name("Credit Reference")
                .build();

        mockOrganisation = new Organisation(ORG_ID,"testOrg","testCity","testPostCode","testProvince","testAddress","testPhone","testTaxId","IE","00000000",false,false,7305,"ISO_4217:CHF","ISO_4217:CHF","http://testWeb","email@test.com",null);
        mockEventCodeUpdate = new EventCodeUpdate(DEBIT_REF_CODE, CREDIT_REF_CODE, "Updated Name", true);
    }

    @Test
    void insertAccountEventByCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, EventCodeUpdate.class)).thenReturn(Either.left(Problem.builder().build()));

        Either<Set<Problem>, Set<AccountEventView>> ret = accountEventService.insertAccountEventByCsv(ORG_ID, file);

        assertTrue(ret.isLeft());
        assertEquals(1, ret.getLeft().size());
    }

    @Test
    void insertAccountEventByCsv_insertError() {
        MultipartFile file = mock(MultipartFile.class);
        EventCodeUpdate update = mock(EventCodeUpdate.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(update)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        when(update.getDebitReferenceCode()).thenReturn(DEBIT_REF_CODE);
        when(update.getCreditReferenceCode()).thenReturn(CREDIT_REF_CODE);
        when(csvParser.parseCsv(file, EventCodeUpdate.class)).thenReturn(Either.right(List.of(update)));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        Either<Set<Problem>, Set<AccountEventView>> sets = accountEventService.insertAccountEventByCsv(ORG_ID, file);

        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
    }

    @Test
    void insertAccountEventByCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);
        EventCodeUpdate update = mock(EventCodeUpdate.class);

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(update)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        when(csvParser.parseCsv(file, EventCodeUpdate.class)).thenReturn(Either.right(List.of(update)));

        Either<Set<Problem>, Set<AccountEventView>> sets = accountEventService.insertAccountEventByCsv(ORG_ID, file);

        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
        assertTrue(sets.get().iterator().next().getError().isPresent());
        assertEquals("Default Message", sets.get().iterator().next().getError().get().getDetail());
    }

    @Test
    void insertAccountEventByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        EventCodeUpdate update = mock(EventCodeUpdate.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(update)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        when(update.getDebitReferenceCode()).thenReturn(DEBIT_REF_CODE);
        when(update.getCreditReferenceCode()).thenReturn(CREDIT_REF_CODE);
        when(csvParser.parseCsv(file, EventCodeUpdate.class)).thenReturn(Either.right(List.of(update)));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.of(mockCreditReference));
        when(accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE))
                .thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        when(accountEventRepository.save(any(AccountEvent.class))).thenReturn(mockAccountEvent);

        Either<Set<Problem>, Set<AccountEventView>> sets = accountEventService.insertAccountEventByCsv(ORG_ID, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
    }

    @Test
    void testFindById_AndActive_Found() {
        when(accountEventRepository.findByIdAndActive(accountEventId,true)).thenReturn(Optional.of(mockAccountEvent));

        Optional<AccountEvent> result = accountEventService.findByIdAndActive(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE);

        assertTrue(result.isPresent());
        assertEquals(mockAccountEvent, result.get());
        verify(accountEventRepository).findByIdAndActive(accountEventId,true);
    }

    @Test
    void testFindById_AndActive_NotFound() {
        when(accountEventRepository.findByIdAndActive(accountEventId, true)).thenReturn(Optional.empty());

        Optional<AccountEvent> result = accountEventService.findByIdAndActive(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE);

        assertFalse(result.isPresent());
        verify(accountEventRepository).findByIdAndActive(accountEventId,true);
    }

    @Test
    void testGetAllEventCodes() {
        when(accountEventRepository.findAllByOrganisationId(ORG_ID, null, null, null, null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(mockAccountEvent)));
        when(jpaSortFieldValidator.validateEntity(AccountEvent.class, Pageable.unpaged(), ACCOUNT_EVENT_MAPPINGS)).thenReturn(Either.right(Pageable.unpaged()));
        Either<Problem, List<AccountEventView>> result = accountEventService.getAllAccountEvent(ORG_ID, null, null, null, null, null, Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        verify(accountEventRepository).findAllByOrganisationId(ORG_ID, null, null, null, null, null, Pageable.unpaged());
    }

    @Test
    void testUpdateReferenceCode_Successful() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.of(mockCreditReference));
        when(accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE))
                .thenReturn(Optional.of(mockAccountEvent));
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        when(accountEventRepository.save(any(AccountEvent.class))).thenReturn(mockAccountEvent);


        AccountEventView result = accountEventService.updateAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isEmpty());
        assertEquals("Updated Name", result.getDescription());
        verify(accountEventRepository).save(any(AccountEvent.class));
    }

    @Test
    void testUpdateReferenceCode_CodeNotExist() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.of(mockCreditReference));
        when(accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE))
                .thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        AccountEventView result = accountEventService.updateAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("ACCOUNT_EVENT_NOT_FOUND", result.getError().get().getTitle());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testUpdateReferenceCode_FailsWhenDebitReferenceMissing() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        AccountEventView result = accountEventService.updateAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("REFERENCE_CODE_NOT_FOUND", result.getError().get().getTitle());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testUpdateReferenceCode_FailsWhenCreditReferenceMissing() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        AccountEventView result = accountEventService.updateAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("REFERENCE_CODE_NOT_FOUND", result.getError().get().getTitle());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testUpdateReferenceCode_FailsWhenOrganisationMissing() {
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.empty());

        AccountEventView result = accountEventService.updateAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isPresent());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testInsertReferenceCode_Successful() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.of(mockCreditReference));
        when(accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE))
                .thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        when(accountEventRepository.save(any(AccountEvent.class))).thenReturn(mockAccountEvent);


        AccountEventView result = accountEventService.insertAccountEvent(ORG_ID, mockEventCodeUpdate, false);

        assertTrue(result.getError().isEmpty());
        assertEquals("Test Event", result.getDescription());
        verify(accountEventRepository).save(any(AccountEvent.class));
    }

    @Test
    void testInsertReferenceCode_CodeAlreadyExist() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.of(mockCreditReference));
        when(accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE))
                .thenReturn(Optional.of(mockAccountEvent));
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        AccountEventView result = accountEventService.insertAccountEvent(ORG_ID, mockEventCodeUpdate, false);

        assertTrue(result.getError().isPresent());
        assertEquals("ACCOUNT_EVENT_ALREADY_EXISTS", result.getError().get().getTitle());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testInsertReferenceCode_FailsWhenDebitReferenceMissing() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        AccountEventView result = accountEventService.insertAccountEvent(ORG_ID, mockEventCodeUpdate, false);

        assertTrue(result.getError().isPresent());
        assertEquals("REFERENCE_CODE_NOT_FOUND", result.getError().get().getTitle());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testInsertReferenceCode_FailsWhenCreditReferenceMissing() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        AccountEventView result = accountEventService.insertAccountEvent(ORG_ID, mockEventCodeUpdate, false);

        assertTrue(result.getError().isPresent());
        assertEquals("REFERENCE_CODE_NOT_FOUND", result.getError().get().getTitle());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testInsertReferenceCode_FailsWhenOrganisationMissing() {
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.empty());

        AccountEventView result = accountEventService.insertAccountEvent(ORG_ID, mockEventCodeUpdate, false);

        assertTrue(result.getError().isPresent());
        verify(accountEventRepository, never()).save(any());
    }
}
