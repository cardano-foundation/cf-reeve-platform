package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;
import org.cardanofoundation.lob.app.organisation.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationChartOfAccountView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;

@ExtendWith(MockitoExtension.class)
class ChartOfAccountsServiceTest {

    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;

    @Mock
    private OrganisationChartOfAccountTypeRepository organisationChartOfAccountTypeRepository;

    @Mock
    private OrganisationChartOfAccountSubTypeRepository organisationChartOfAccountSubTypeRepository;

    @Mock
    private ReferenceCodeRepository referenceCodeRepository;

    @Mock
    private OrganisationService organisationService;

    private ChartOfAccountsService chartOfAccountsService;

    private final String orgId = "12345";
    private final String customerCode = "CUST001";

    private OrganisationChartOfAccount chartOfAccount;
    private ChartOfAccountUpdate chartOfAccountUpdate;
    private ReferenceCode referenceCode;
    private OrganisationChartOfAccountSubType subType;
    private OrganisationChartOfAccountType type;
    private OrganisationChartOfAccount.Id chartOfAccountId;

    @BeforeEach
    void setUp() {
        chartOfAccountsService = new ChartOfAccountsService(
                chartOfAccountRepository,
                organisationChartOfAccountTypeRepository,
                organisationChartOfAccountSubTypeRepository,
                referenceCodeRepository,
                organisationService
        );
        chartOfAccountId = new OrganisationChartOfAccount.Id(orgId, customerCode);
        chartOfAccount = OrganisationChartOfAccount.builder()
                .id(chartOfAccountId)
                .name("Test Account")
                .eventRefCode("EVT123")
                .build();

        referenceCode = new ReferenceCode(new ReferenceCode.Id(orgId, "EVT123"), null, null, "RefCode", true);
        subType = new OrganisationChartOfAccountSubType();
        type = new OrganisationChartOfAccountType();
        subType.setType(type);

        chartOfAccount.setSubType(subType);

        chartOfAccountUpdate = new ChartOfAccountUpdate(customerCode, "EVT123", "REF123", "Description", "3", "USD", "CounterParty", "2", null, Boolean.TRUE, new OpeningBalance(BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), "USD", "USD", OperationType.DEBIT, LocalDate.now()));
    }

    @Test
    void testGetChartAccount_Found() {
        when(chartOfAccountRepository.findById(any())).thenReturn(Optional.of(chartOfAccount));

        Optional<OrganisationChartOfAccount> result = chartOfAccountsService.getChartAccount(orgId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(chartOfAccountId, result.get().getId());
    }

    @Test
    void testGetChartAccount_NotFound() {
        when(chartOfAccountRepository.findById(any())).thenReturn(Optional.empty());

        Optional<OrganisationChartOfAccount> result = chartOfAccountsService.getChartAccount(orgId, customerCode);

        assertFalse(result.isPresent());
    }

    @Test
    void testGetAllChartType() {
        when(organisationChartOfAccountTypeRepository.findAllByOrganisationId(orgId)).thenReturn(Set.of(type));

        Set<OrganisationChartOfAccountType> result = chartOfAccountsService.getAllChartType(orgId);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetBySubTypeId() {
        when(chartOfAccountRepository.findAllByOrganisationIdSubTypeId(1L)).thenReturn(Set.of(chartOfAccount));

        Set<OrganisationChartOfAccount> result = chartOfAccountsService.getBySubTypeId(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetAllChartOfAccount() {
        Set<OrganisationChartOfAccount> accounts = Set.of(chartOfAccount);
        when(chartOfAccountRepository.findAllByOrganisationId(orgId)).thenReturn(accounts);

        Set<OrganisationChartOfAccountView> result = chartOfAccountsService.getAllChartOfAccount(orgId);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testUpsertChartOfAccount_Success() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.of(chartOfAccount));
        when(chartOfAccountRepository.save(any(OrganisationChartOfAccount.class))).thenReturn(chartOfAccount);

        OrganisationChartOfAccountView response = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertTrue(response.getError().isEmpty());
    }

    @Test
    void testUpsertChartOfAccount_OrganisationNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.empty());

        OrganisationChartOfAccountView response = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("ORGANISATION_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpsertChartOfAccount_ReferenceCodeNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.empty());

        OrganisationChartOfAccountView response = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("REFERENCE_CODE_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpsertChartOfAccount_SubTypeNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.empty());

        OrganisationChartOfAccountView response = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("SUBTYPE_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpsertChartOfAccount_ParentAccountNotFound() {
        chartOfAccountUpdate.setParentCustomerCode("PARENT001");
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode()))
                .thenReturn(Optional.empty());

        OrganisationChartOfAccountView response = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("PARENT_ACCOUNT_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpsertChartOfAccount_NewAccountCreation() {
        OrganisationChartOfAccount.Id accountId = new OrganisationChartOfAccount.Id(orgId, customerCode);
        OrganisationChartOfAccount newAccount = OrganisationChartOfAccount.builder().id(accountId).subType(subType).build();

        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(organisationChartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.empty());
        when(chartOfAccountRepository.save(any(OrganisationChartOfAccount.class))).thenReturn(newAccount);

        OrganisationChartOfAccountView response = chartOfAccountsService.upsertChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertTrue(response.getError().isEmpty());
    }
}
