package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

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

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCostCenter;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationCostCenterView;
import org.cardanofoundation.lob.app.organisation.service.CostCenterService;

@ExtendWith(MockitoExtension.class)
class CostCenterControllerTest {

    @Mock
    private CostCenterService costCenterService;

    @InjectMocks
    private CostCenterController costCenterController;

    @Test
    void getAllCostCenter() {
        // Mock the service call
        when(costCenterService.getAllCostCenter("org123")).thenReturn(Set.of(mock(OrganisationCostCenter.class)));

        // Call the controller method
        ResponseEntity<Set<OrganisationCostCenterView>> response = costCenterController.getAllCostCenters("org123");

        // Verify the response
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void insertCostCenters_success() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);
        // Mock the service call
        when(costCenterService.insertCostCenter("org123", costCenterUpdate))
                .thenReturn(mock(OrganisationCostCenterView.class));

        // Call the controller method
        ResponseEntity<OrganisationCostCenterView> response = costCenterController.insertCostCenters("org123", costCenterUpdate);

        // Verify the response
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(OrganisationCostCenterView.class, response.getBody().getClass());
    }

    @Test
    void updateCostCenters_success() {
        CostCenterUpdate costCenterUpdate = mock(CostCenterUpdate.class);
        // Mock the service call
        when(costCenterService.updateCostCenter("org123", costCenterUpdate))
                .thenReturn(mock(OrganisationCostCenterView.class));

        // Call the controller method
        ResponseEntity<OrganisationCostCenterView> response = costCenterController.updateCostCenters("org123", costCenterUpdate);

        // Verify the response
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(OrganisationCostCenterView.class, response.getBody().getClass());
    }

    @Test
    void insertCostCentersCsv_problem() {
        MultipartFile file = mock(MultipartFile.class);
        when(costCenterService.createCostCenterFromCsv("org123", file)).thenReturn(Either.left(Problem.builder().withStatus(Status.BAD_REQUEST).build()));

        ResponseEntity<?> response = costCenterController.insertCostCentersCsv("org123", file);
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void insertCostCentersCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        List<OrganisationCostCenterView> costCenterViews = List.of(mock(OrganisationCostCenterView.class));
        when(costCenterService.createCostCenterFromCsv("org123", file)).thenReturn(Either.right(costCenterViews));

        ResponseEntity<?> response = costCenterController.insertCostCentersCsv("org123", file);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(costCenterViews, response.getBody());
    }

}
