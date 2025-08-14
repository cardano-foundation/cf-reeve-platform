package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.view.CostCenterView;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class CostCenterServiceTest {

    @Mock
    private CostCenterRepository costCenterRepository;
    @Mock
    private CsvParser<CostCenterUpdate> csvParser;
    @Mock
    private Validator validator;

    @InjectMocks
    private CostCenterService costCenterService;

    private final String organisationId = "org123";
    private final String customerCode = "cust001";
    private CostCenter.Id costCenterId;
    private CostCenter costCenter;

    @BeforeEach
    void setUp() {
        costCenterId = new CostCenter.Id(organisationId, customerCode);
        costCenter = CostCenter.builder()
                .id(costCenterId)
                .name("Test Cost Center")
                .build();
    }

    @Test
    void testGetCostCenter_Found() {
        when(costCenterRepository.findByIdAndActive(costCenterId, true)).thenReturn(Optional.of(costCenter));

        Optional<CostCenter> result = costCenterService.getCostCenter(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(costCenter, result.get());
        verify(costCenterRepository).findByIdAndActive(costCenterId, true);
    }

    @Test
    void testGetCostCenter_NotFound() {
        when(costCenterRepository.findByIdAndActive(costCenterId, true)).thenReturn(Optional.empty());

        Optional<CostCenter> result = costCenterService.getCostCenter(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(costCenterRepository).findByIdAndActive(costCenterId, true);
    }

    @Test
    void testGetAllCostCenter() {
        Set<CostCenter> costCenters = Set.of(costCenter);
        when(costCenterRepository.findAllByOrganisationIdWithParentAndChildren(organisationId)).thenReturn(costCenters);

        Set<CostCenter> result = costCenterService.getAllCostCenter(organisationId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(costCenter));
        verify(costCenterRepository).findAllByOrganisationIdWithParentAndChildren(organisationId);
    }

    @Test
    void updateCostCenter_costCenterNotFound() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);

        when(costCenterUpdate.getCustomerCode()).thenReturn("customerCode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customerCode"))).thenReturn(Optional.empty());

        CostCenterView costCenterView = costCenterService.updateCostCenter(organisationId, costCenterUpdate);

        assertNotNull(costCenterView);
        assertEquals("customerCode", costCenterView.getCustomerCode());
        assertEquals("COST_CENTER_CODE_NOT_FOUND", costCenterView.getError().get().getTitle());
    }

    @Test
    void updateCostCenter_parentNotFound() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);

        when(costCenterUpdate.getCustomerCode()).thenReturn("customercode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customercode"))).thenReturn(Optional.of(costCenter));
        when(costCenterUpdate.getParentCustomerCode()).thenReturn("parentcode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "parentcode"))).thenReturn(Optional.empty());

        CostCenterView costCenterView = costCenterService.updateCostCenter(organisationId, costCenterUpdate);

        assertNotNull(costCenterView);
        assertEquals("customercode", costCenterView.getCustomerCode());
        assertEquals("PARENT_COST_CENTER_CODE_NOT_FOUND", costCenterView.getError().get().getTitle());
    }

    @Test
    void updateCostCenter_success() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);
        CostCenter parentMock = mock(CostCenter.class);
        when(costCenterUpdate.getCustomerCode()).thenReturn("customercode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customercode"))).thenReturn(Optional.of(costCenter));
        when(costCenterUpdate.getParentCustomerCode()).thenReturn("parentCustomerCode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "parentCustomerCode"))).thenReturn(Optional.of(parentMock));
        when(costCenterUpdate.getName()).thenReturn("Test Cost Center");
        when(costCenterRepository.save(any())).thenReturn(costCenter);

        CostCenterView costCenterView = costCenterService.updateCostCenter(organisationId, costCenterUpdate);

        assertNotNull(costCenterView);
        assertEquals(costCenter.getId().getCustomerCode(), costCenterView.getCustomerCode());
        assertEquals(costCenter.getName(), costCenterView.getName());
    }

    @Test
    void insertCostCenter_costCenterAlreadyExists() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);

        when(costCenterUpdate.getCustomerCode()).thenReturn("customercode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customercode"))).thenReturn(Optional.of(costCenter));

        CostCenterView costCenterView = costCenterService.insertCostCenter(organisationId, costCenterUpdate, false);

        assertNotNull(costCenterView);
        assertEquals("customercode", costCenterView.getCustomerCode());
        assertEquals("COST_CENTER_CODE_ALREADY_EXISTS", costCenterView.getError().get().getTitle());
    }

    @Test
    void insertCostCenter_parentNotFound() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);

        when(costCenterUpdate.getCustomerCode()).thenReturn("customercode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customercode"))).thenReturn(Optional.empty());
        when(costCenterUpdate.getParentCustomerCode()).thenReturn("parentcode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "parentcode"))).thenReturn(Optional.empty());

        CostCenterView costCenterView = costCenterService.insertCostCenter(organisationId, costCenterUpdate, false);

        assertNotNull(costCenterView);
        assertEquals("customercode", costCenterView.getCustomerCode());
        assertEquals("PARENT_COST_CENTER_CODE_NOT_FOUND", costCenterView.getError().get().getTitle());
    }

    @Test
    void insertCostCenter_success() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);
        CostCenter parentMock = mock(CostCenter.class);
        when(costCenterUpdate.getCustomerCode()).thenReturn("customercode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customercode"))).thenReturn(Optional.empty());
        when(costCenterUpdate.getParentCustomerCode()).thenReturn("parentCustomerCode");
        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "parentCustomerCode"))).thenReturn(Optional.of(parentMock));
        when(costCenterUpdate.getName()).thenReturn("Test Cost Center");
        when(costCenterRepository.save(any())).thenReturn(costCenter);
        when(parentMock.getId()).thenReturn(new CostCenter.Id(organisationId, "parentCustomerCode"));

        CostCenterView costCenterView = costCenterService.insertCostCenter(organisationId, costCenterUpdate, false);

        assertNotNull(costCenterView);
        assertEquals(costCenter.getId().getCustomerCode(), costCenterView.getCustomerCode());
        assertEquals(costCenter.getName(), costCenterView.getName());
    }

    @Test
    void createCostCenterFromCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, CostCenterUpdate.class)).thenReturn(Either.left(Problem.builder().withTitle("Parse Error").build()));

        Either<Problem, List<CostCenterView>> result = costCenterService.createCostCenterFromCsv(organisationId, file);
        assertTrue(result.isLeft());
        assertEquals("Parse Error", result.getLeft().getTitle());
    }

    @Test
    void createCostCenterFromCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(costCenterUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        when(costCenterRepository.findById(new CostCenter.Id(organisationId, "customercode"))).thenReturn(Optional.empty());
        when(costCenterUpdate.getCustomerCode()).thenReturn("customercode");
        when(costCenterUpdate.getParentCustomerCode()).thenReturn(null);
        when(costCenterUpdate.getName()).thenReturn("Test Cost Center");
        when(csvParser.parseCsv(file, CostCenterUpdate.class)).thenReturn(Either.right(List.of(costCenterUpdate)));
        when(costCenterRepository.save(any())).thenReturn(costCenter);

        Either<Problem, List<CostCenterView>> result = costCenterService.createCostCenterFromCsv(organisationId, file);
        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals(costCenter.getName(), result.get().get(0).getName());
    }

    @Test
    void createCostCenterFromCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(costCenterUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        when(csvParser.parseCsv(file, CostCenterUpdate.class)).thenReturn(Either.right(List.of(costCenterUpdate)));

        Either<Problem, List<CostCenterView>> result = costCenterService.createCostCenterFromCsv(organisationId, file);
        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals("Default Message", result.get().get(0).getError().get().getDetail());
    }
}
