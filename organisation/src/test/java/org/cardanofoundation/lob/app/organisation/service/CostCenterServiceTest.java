package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCostCenter;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;

@ExtendWith(MockitoExtension.class)
class CostCenterServiceTest {

    @Mock
    private CostCenterRepository costCenterRepository;

    @InjectMocks
    private CostCenterService costCenterService;

    private final String organisationId = "org123";
    private final String customerCode = "cust001";
    private OrganisationCostCenter.Id costCenterId;
    private OrganisationCostCenter costCenter;

    @BeforeEach
    void setUp() {
        costCenterId = new OrganisationCostCenter.Id(organisationId, customerCode);
        costCenter = OrganisationCostCenter.builder()
                .id(costCenterId)
                .name("Test Cost Center")
                .build();
    }

    @Test
    void testGetCostCenter_Found() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(costCenter));

        Optional<OrganisationCostCenter> result = costCenterService.getCostCenter(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(costCenter, result.get());
        verify(costCenterRepository).findById(costCenterId);
    }

    @Test
    void testGetCostCenter_NotFound() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.empty());

        Optional<OrganisationCostCenter> result = costCenterService.getCostCenter(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(costCenterRepository).findById(costCenterId);
    }

    @Test
    void testGetAllCostCenter() {
        Set<OrganisationCostCenter> costCenters = Set.of(costCenter);
        when(costCenterRepository.findAllByOrganisationId(organisationId)).thenReturn(costCenters);

        Set<OrganisationCostCenter> result = costCenterService.getAllCostCenter(organisationId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(costCenter));
        verify(costCenterRepository).findAllByOrganisationId(organisationId);
    }
}
