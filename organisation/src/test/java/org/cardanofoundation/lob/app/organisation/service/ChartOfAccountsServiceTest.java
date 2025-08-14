package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.CHART_OF_ACCOUNT_MAPPINGS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;
import org.cardanofoundation.lob.app.organisation.domain.csv.ChartOfAccountUpdateCsv;
import org.cardanofoundation.lob.app.organisation.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ChartOfAccountView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.CurrencyRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.JpaSortFieldValidator;

@ExtendWith(MockitoExtension.class)
class ChartOfAccountsServiceTest {

    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;

    @Mock
    private ChartOfAccountTypeRepository chartOfAccountTypeRepository;

    @Mock
    private ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;

    @Mock
    private ReferenceCodeRepository referenceCodeRepository;

    @Mock
    private OrganisationService organisationService;
    @Mock
    private CsvParser<ChartOfAccountUpdateCsv> csvParser;
    @Mock
    private Validator validator;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    private ChartOfAccountsService chartOfAccountsService;

    private final String orgId = "12345";
    private final String customerCode = "CUST001";

    private ChartOfAccount chartOfAccount;
    private ChartOfAccountUpdate chartOfAccountUpdate;
    private ReferenceCode referenceCode;
    private ChartOfAccountSubType subType;
    private ChartOfAccountType type;
    private ChartOfAccount.Id chartOfAccountId;

    @BeforeEach
    void setUp() {
        chartOfAccountsService = new ChartOfAccountsService(
                chartOfAccountRepository,
                chartOfAccountTypeRepository,
                chartOfAccountSubTypeRepository,
                referenceCodeRepository,
                currencyRepository,
                organisationService,
                csvParser,
                validator,
                jpaSortFieldValidator
        );
        chartOfAccountId = new ChartOfAccount.Id(orgId, customerCode);
        chartOfAccount = ChartOfAccount.builder()
                .id(chartOfAccountId)
                .name("Test Account")
                .eventRefCode("EVT123")
                .build();

        referenceCode = new ReferenceCode(new ReferenceCode.Id(orgId, "EVT123"), null, null, "RefCode", true);
        subType = new ChartOfAccountSubType();
        type = new ChartOfAccountType();
        subType.setType(type);

        chartOfAccount.setSubType(subType);

        chartOfAccountUpdate = new ChartOfAccountUpdate(customerCode, "REF123", "Description", "3", "USD", "CounterParty", "2", null, Boolean.TRUE, new OpeningBalance(BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), "USD", "USD", OperationType.DEBIT, LocalDate.now()));
    }

    @Test
    void insertChartOfAccountByCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.left(Problem.builder()
                .withTitle("Error")
                .withStatus(org.zalando.problem.Status.BAD_REQUEST)
                .build()));

        Either<Set<Problem>, Set<ChartOfAccountView>> views = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);

        assertTrue(views.isLeft());
        assertEquals(1, views.getLeft().size());
    }

    @Test
    void insertChartOfAccountByCsv_openingBalanceConvertError() {
        MultipartFile file = mock(MultipartFile.class);

        ChartOfAccountUpdateCsv updateCsv = mock(ChartOfAccountUpdateCsv.class);
        Errors errors = mock(Errors.class);
        when(validator.validateObject(updateCsv)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));
        doThrow(new IllegalArgumentException("Error")).when(updateCsv).fillOpeningBalance();
        Either<Set<Problem>, Set<ChartOfAccountView>> sets = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
        assertEquals("OPENING_BALANCE_ERROR", sets.get().iterator().next().getError().get().getTitle());
    }

    @Test
    void insertChartOfAccountByCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);

        ChartOfAccountUpdateCsv updateCsv = mock(ChartOfAccountUpdateCsv.class);
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(updateCsv)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));

        Either<Set<Problem>, Set<ChartOfAccountView>> sets = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
        assertEquals("VALIDATION_ERROR", sets.get().iterator().next().getError().get().getTitle());
    }

    @Test
    void insertChartOfAccountByCsv_organisationNotFound() {
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccountUpdateCsv updateCsv = mock(ChartOfAccountUpdateCsv.class);
        ChartOfAccountType typeMock = mock(ChartOfAccountType.class);
        ChartOfAccountSubType subTypeMock = mock(ChartOfAccountSubType.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(updateCsv)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));
        when(updateCsv.getSubType()).thenReturn("SUBTYPE");
        when(updateCsv.getType()).thenReturn("TYPE");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, "TYPE")).thenReturn(Optional.of(typeMock));
        when(chartOfAccountSubTypeRepository.findFirstByNameAndOrganisationIdAndParentName(orgId, "SUBTYPE", "TYPE")).thenReturn(Optional.of(subTypeMock));

        Either<Set<Problem>, Set<ChartOfAccountView>> sets = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
        assertEquals("ORGANISATION_NOT_FOUND", sets.get().iterator().next().getError().get().getTitle());
    }

    @Test
    void insertChartOfAccountByCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccountUpdateCsv updateCsv = mock(ChartOfAccountUpdateCsv.class);
        ChartOfAccountType typeMock = mock(ChartOfAccountType.class);
        ChartOfAccountSubType subTypeMock = mock(ChartOfAccountSubType.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(updateCsv)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(subTypeMock.getId()).thenReturn(3L);
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));
        when(updateCsv.getSubType()).thenReturn("SUBTYPE");
        when(updateCsv.getType()).thenReturn("TYPE");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, "TYPE")).thenReturn(Optional.of(typeMock));
        when(chartOfAccountSubTypeRepository.findFirstByNameAndOrganisationIdAndParentName(orgId, "SUBTYPE", "TYPE")).thenReturn(Optional.of(subTypeMock));
        when(updateCsv.getEventRefCode()).thenReturn(chartOfAccountUpdate.getEventRefCode());

        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, "SUBTYPE"))
                .thenReturn(Optional.of(subTypeMock));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, null))
                .thenReturn(Optional.empty());
        when(chartOfAccountRepository.save(any(ChartOfAccount.class))).thenReturn(chartOfAccount);

        Either<Set<Problem>, Set<ChartOfAccountView>> sets = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
    }

    @Test
    void insertChartOfAccountByCsv_typeNotFound() {
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccountUpdateCsv updateCsv = mock(ChartOfAccountUpdateCsv.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(updateCsv)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));
        when(updateCsv.getSubType()).thenReturn("SUBTYPE");
        when(updateCsv.getType()).thenReturn("TYPE");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, "TYPE")).thenReturn(Optional.empty());
        when(updateCsv.getEventRefCode()).thenReturn(chartOfAccountUpdate.getEventRefCode());

        Either<Set<Problem>, Set<ChartOfAccountView>> sets = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
    }

    @Test
    void insertChartOfAccountByCsv_subTypeNotFound() {
        MultipartFile file = mock(MultipartFile.class);
        ChartOfAccountUpdateCsv updateCsv = mock(ChartOfAccountUpdateCsv.class);
        ChartOfAccountType typeMock = mock(ChartOfAccountType.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(updateCsv)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class)).thenReturn(Either.right(List.of(updateCsv)));
        when(updateCsv.getSubType()).thenReturn("SUBTYPE");
        when(updateCsv.getType()).thenReturn("TYPE");
        when(chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, "TYPE")).thenReturn(Optional.of(typeMock));
        when(chartOfAccountSubTypeRepository.findFirstByNameAndOrganisationIdAndParentName(orgId, "SUBTYPE", "TYPE")).thenReturn(Optional.empty());
        when(updateCsv.getEventRefCode()).thenReturn(chartOfAccountUpdate.getEventRefCode());

        Either<Set<Problem>, Set<ChartOfAccountView>> sets = chartOfAccountsService.insertChartOfAccountByCsv(orgId, file);
        assertTrue(sets.isRight());
        assertEquals(1, sets.get().size());
    }

    @Test
    void testGetChartAccount_Found() {
        when(chartOfAccountRepository.findByIdAndActive(any(),eq(true))).thenReturn(Optional.of(chartOfAccount));

        Optional<ChartOfAccount> result = chartOfAccountsService.getChartAccount(orgId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(chartOfAccountId, result.get().getId());
    }

    @Test
    void testGetChartAccount_NotFound() {
        when(chartOfAccountRepository.findByIdAndActive(any(),eq(true))).thenReturn(Optional.empty());

        Optional<ChartOfAccount> result = chartOfAccountsService.getChartAccount(orgId, customerCode);

        assertFalse(result.isPresent());
    }

    @Test
    void testGetAllChartType() {
        when(chartOfAccountTypeRepository.findAllByOrganisationId(orgId)).thenReturn(Set.of(type));

        Set<ChartOfAccountType> result = chartOfAccountsService.getAllChartType(orgId);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetBySubTypeId() {
        when(chartOfAccountRepository.findAllByOrganisationIdSubTypeId(1L)).thenReturn(Set.of(chartOfAccount));

        Set<ChartOfAccount> result = chartOfAccountsService.getBySubTypeId(1L);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetAllChartOfAccount() {
        List<ChartOfAccount> accounts = List.of(chartOfAccount);
        when(chartOfAccountRepository.findAllByOrganisationIdFiltered(orgId,  null, null, null, null, null,  null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(accounts));
        when(jpaSortFieldValidator.validateEntity(ChartOfAccount.class, Pageable.unpaged(), CHART_OF_ACCOUNT_MAPPINGS)).thenReturn(Either.right(Pageable.unpaged()));
        Either<Problem, List<ChartOfAccountView>> result = chartOfAccountsService.getAllChartOfAccount(orgId,  null, null, null, null, null, null, null, Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
    }

    @Test
    void testUpdateChartOfAccount_Success() {
        Organisation mockOrg = mock(Organisation.class);
        when(organisationService.findById(orgId)).thenReturn(Optional.of(mockOrg));
        when(mockOrg.getCurrencyId()).thenReturn("USD");
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.of(chartOfAccount));
        when(chartOfAccountRepository.save(any(ChartOfAccount.class))).thenReturn(chartOfAccount);
        Currency currency = mock(Currency.class);
        when(currencyRepository.findById(any())).thenReturn(Optional.of(currency));
        when(currencyRepository.findByCurrencyId(orgId, "USD")).thenReturn(Optional.of(currency));
        when(currency.getId()).thenReturn(new Currency.Id(orgId, "USD"));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(chartOfAccountUpdate.getOpeningBalance())).thenReturn(errors);

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertTrue(response.getError().isEmpty());
    }

    @Test
    void testUpdateChartOfAccount_openingBalanceLCYMismatch() {
        Organisation mockOrg = mock(Organisation.class);
        when(organisationService.findById(orgId)).thenReturn(Optional.of(mockOrg));
        when(mockOrg.getCurrencyId()).thenReturn("EUR");

        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.of(chartOfAccount));
        Currency currency = mock(Currency.class);
        when(currencyRepository.findById(any())).thenReturn(Optional.of(currency));
        when(currencyRepository.findByCurrencyId(orgId, "EUR")).thenReturn(Optional.of(currency));
        when(currency.getId()).thenReturn(new Currency.Id(orgId, "EUR"));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(chartOfAccountUpdate.getOpeningBalance())).thenReturn(errors);

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertTrue(response.getError().isPresent());
        assertEquals("OPENING_BALANCE_CURRENCY_MISMATCH", response.getError().get().getTitle());
    }

    @Test
    void testUpdateChartOfAccount_OrganisationNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("ORGANISATION_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpdateChartOfAccount_ReferenceCodeNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("REFERENCE_CODE_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpdateChartOfAccount_SubTypeNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("SUBTYPE_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpdateChartOfAccount_ParentAccountNotFound() {
        chartOfAccountUpdate.setParentCustomerCode("PARENT001");
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("PARENT_ACCOUNT_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testUpdateChartOfAccount_NoExist() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.updateChartOfAccount(orgId, chartOfAccountUpdate);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("CHART_OF_ACCOUNT_NOT_FOUND", response.getError().get().getTitle());
    }

    // NUEVO

    @Test
    void testInsertChartOfAccount_Exist() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.of(chartOfAccount));

        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("CHART_OF_ACCOUNT_ALREADY_EXISTS", response.getError().get().getTitle());
    }

    @Test
    void testInsertChartOfAccount_Success() {
        Organisation mockOrg = mock(Organisation.class);
        when(organisationService.findById(orgId)).thenReturn(Optional.of(mockOrg));
        when(mockOrg.getCurrencyId()).thenReturn("USD");
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.empty());
        when(chartOfAccountRepository.save(any(ChartOfAccount.class))).thenReturn(chartOfAccount);
        Currency currency = mock(Currency.class);
        when(currencyRepository.findById(any())).thenReturn(Optional.of(currency));

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(chartOfAccountUpdate.getOpeningBalance())).thenReturn(errors);

        when(currencyRepository.findByCurrencyId(orgId, "USD")).thenReturn(Optional.of(currency));
        when(currency.getId()).thenReturn(new Currency.Id(orgId, "USD"));
        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertTrue(response.getError().isEmpty());
    }

    @Test
    void testInsertChartOfAccount_currencyNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.empty());
        when(currencyRepository.findById(any())).thenReturn(Optional.empty());
        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertTrue(response.getError().isPresent());
        assertEquals("CURRENCY_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testInsertChartOfAccount_OrganisationNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("ORGANISATION_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testInsertChartOfAccount_ReferenceCodeNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("REFERENCE_CODE_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testInsertChartOfAccount_SubTypeNotFound() {
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("SUBTYPE_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testInsertChartOfAccount_ParentAccountNotFound() {
        chartOfAccountUpdate.setParentCustomerCode("PARENT001");
        when(organisationService.findById(orgId)).thenReturn(Optional.of(new Organisation()));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode()))
                .thenReturn(Optional.empty());

        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertFalse(response.getError().isEmpty());
        assertEquals("PARENT_ACCOUNT_NOT_FOUND", response.getError().get().getTitle());
    }

    @Test
    void testInsertChartOfAccount_NewAccountCreation() {
        ChartOfAccount.Id accountId = new ChartOfAccount.Id(orgId, customerCode);
        ChartOfAccount newAccount = ChartOfAccount.builder().id(accountId).subType(subType).build();

        Organisation mockOrg = mock(Organisation.class);
        when(organisationService.findById(orgId)).thenReturn(Optional.of(mockOrg));
        when(mockOrg.getCurrencyId()).thenReturn("USD");
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.empty());
        when(chartOfAccountRepository.save(any(ChartOfAccount.class))).thenReturn(newAccount);
        Currency currency = mock(Currency.class);
        when(currencyRepository.findById(any())).thenReturn(Optional.of(currency));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(chartOfAccountUpdate.getOpeningBalance())).thenReturn(errors);

        when(currencyRepository.findByCurrencyId(orgId, "USD")).thenReturn(Optional.of(currency));
        when(currency.getId()).thenReturn(new Currency.Id(orgId, "USD"));
        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertTrue(response.getError().isEmpty());
    }

    @Test
    void testInsertChartOfAccount_validationError() {
        ChartOfAccount.Id accountId = new ChartOfAccount.Id(orgId, customerCode);

        Organisation mockOrg = mock(Organisation.class);
        when(organisationService.findById(orgId)).thenReturn(Optional.of(mockOrg));

        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode()))
                .thenReturn(Optional.of(referenceCode));
        when(chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType()))
                .thenReturn(Optional.of(subType));
        when(chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode()))
                .thenReturn(Optional.empty());
        Currency currency = mock(Currency.class);
        when(currencyRepository.findById(any())).thenReturn(Optional.of(currency));
        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(chartOfAccountUpdate.getOpeningBalance())).thenReturn(errors);
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        ChartOfAccountView response = chartOfAccountsService.insertChartOfAccount(orgId, chartOfAccountUpdate, false);

        assertNotNull(response);
        assertTrue(response.getError().isPresent());
    }
}
