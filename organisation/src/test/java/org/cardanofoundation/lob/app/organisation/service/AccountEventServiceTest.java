package org.cardanofoundation.lob.app.organisation.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.repository.AccountEventRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;

@ExtendWith(MockitoExtension.class)
class AccountEventServiceTest {

    @Mock
    private AccountEventRepository accountEventRepository;

    @Mock
    private ReferenceCodeRepository referenceCodeRepository;

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

    @BeforeEach
    void setUp() {
        accountEventId = new AccountEvent.Id(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE);

        mockAccountEvent = AccountEvent.builder()
                .id(accountEventId)
                .customerCode(DEBIT_REF_CODE + CREDIT_REF_CODE)
                .name("Test Event")
                .active(true)
                .hierarchy("1")
                .build();

        mockDebitReference = ReferenceCode.builder()
                .id(new ReferenceCode.Id(ORG_ID, DEBIT_REF_CODE))
                .name("Debit Reference")
                .build();

        mockCreditReference = ReferenceCode.builder()
                .id(new ReferenceCode.Id(ORG_ID, CREDIT_REF_CODE))
                .name("Credit Reference")
                .build();

        mockEventCodeUpdate = new EventCodeUpdate(DEBIT_REF_CODE, CREDIT_REF_CODE, "Updated Name", "2", true);
    }

    @Test
    void testFindById_Found() {
        when(accountEventRepository.findById(accountEventId)).thenReturn(Optional.of(mockAccountEvent));

        Optional<AccountEvent> result = accountEventService.findById(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE);

        assertTrue(result.isPresent());
        assertEquals(mockAccountEvent, result.get());
        verify(accountEventRepository).findById(accountEventId);
    }

    @Test
    void testFindById_NotFound() {
        when(accountEventRepository.findById(accountEventId)).thenReturn(Optional.empty());

        Optional<AccountEvent> result = accountEventService.findById(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE);

        assertFalse(result.isPresent());
        verify(accountEventRepository).findById(accountEventId);
    }

    @Test
    void testGetAllEventCodes() {
        when(accountEventRepository.findAllByOrganisationId(ORG_ID)).thenReturn(Set.of(mockAccountEvent));

        List<AccountEventView> result = accountEventService.getAllAccountEvent(ORG_ID);

        assertEquals(1, result.size());
        verify(accountEventRepository).findAllByOrganisationId(ORG_ID);
    }

    @Test
    void testUpsertReferenceCode_Successful() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.of(mockCreditReference));
        when(accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(ORG_ID, DEBIT_REF_CODE, CREDIT_REF_CODE))
                .thenReturn(Optional.of(mockAccountEvent));

        AccountEventView result = accountEventService.upsertAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isEmpty());
        assertEquals("Updated Name", result.getDescription());
        verify(accountEventRepository).save(any(AccountEvent.class));
    }

    @Test
    void testUpsertReferenceCode_FailsWhenDebitReferenceMissing() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.empty());

        AccountEventView result = accountEventService.upsertAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isEmpty());
        verify(accountEventRepository, never()).save(any());
    }

    @Test
    void testUpsertReferenceCode_FailsWhenCreditReferenceMissing() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, DEBIT_REF_CODE)).thenReturn(Optional.of(mockDebitReference));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, CREDIT_REF_CODE)).thenReturn(Optional.empty());

        AccountEventView result = accountEventService.upsertAccountEvent(ORG_ID, mockEventCodeUpdate);

        assertTrue(result.getError().isEmpty());
        verify(accountEventRepository, never()).save(any());
    }
}
