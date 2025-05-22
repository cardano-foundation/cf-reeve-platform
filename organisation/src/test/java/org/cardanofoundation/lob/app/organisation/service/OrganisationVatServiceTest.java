package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;
import org.cardanofoundation.lob.app.organisation.domain.request.OrganisationVatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationVatView;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationVatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class OrganisationVatServiceTest {

    @Mock
    private OrganisationVatRepository organisationVatRepository;
    @Mock
    private CsvParser<OrganisationVatUpdate> csvParser;

    @InjectMocks
    private OrganisationVatService organisationVatService;

    @Test
    void findByOrganisationAndCode() {
        OrganisationVat.Id id = new OrganisationVat.Id("organisationId", "customerCode");
        OrganisationVat vat = mock(OrganisationVat.class);
        when(organisationVatRepository.findById(id)).thenReturn(Optional.of(vat));

        Optional<OrganisationVat> result = organisationVatService.findByOrganisationAndCode("organisationId", "customerCode");

        assertTrue(result.isPresent());
        assertEquals(vat, result.get());
    }

    @Test
    void insert_alreadyExists() {
        OrganisationVatUpdate organisationVatUpdate = mock(OrganisationVatUpdate.class);
        OrganisationVat.Id id = new OrganisationVat.Id("organisationId", "customerCode");
        when(organisationVatUpdate.getCustomerCode()).thenReturn("customerCode");
        when(organisationVatRepository.findById(id)).thenReturn(Optional.of(mock(OrganisationVat.class)));

        OrganisationVatView result = organisationVatService.insert("organisationId", organisationVatUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_VAT_ALREADY_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void insert_parentNotExists() {
        OrganisationVatUpdate update = mock(OrganisationVatUpdate.class);

        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "parentCustomerCode"))).thenReturn(Optional.empty());

        OrganisationVatView result = organisationVatService.insert("organisationId", update);

        assertTrue(result.getError().isPresent());
        assertEquals("PARENT_ORGANISATION_VAT_DO_NOT_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void insert_success(){
        OrganisationVatUpdate update = mock(OrganisationVatUpdate.class);
        OrganisationVat parent = mock(OrganisationVat.class);
        OrganisationVat saved = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new OrganisationVat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(saved.getActive()).thenReturn(true);

        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "parentCustomerCode"))).thenReturn(Optional.of(parent));
        when(organisationVatRepository.save(any(OrganisationVat.class)))
            .thenReturn(saved);

        OrganisationVatView result = organisationVatService.insert("organisationId", update);

        assertTrue(result.getError().isEmpty());
        assertEquals("customerCode", result.getCustomerCode());
        assertEquals("parentCustomerCode", result.getParentOrganisationVat());
        assertEquals(BigDecimal.ONE.toString(), result.getRate());
        assertEquals("organisationId", result.getOrganisationId());
        assertEquals(true, result.getActive());
    }

    @Test
    void update_notExists() {
        OrganisationVatUpdate organisationVatUpdate = mock(OrganisationVatUpdate.class);
        OrganisationVat.Id id = new OrganisationVat.Id("organisationId", "customerCode");
        when(organisationVatUpdate.getCustomerCode()).thenReturn("customerCode");
        when(organisationVatRepository.findById(id)).thenReturn(Optional.empty());

        OrganisationVatView result = organisationVatService.update("organisationId", organisationVatUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_VAT_DO_NOT_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void update_parentNotExists() {
        OrganisationVatUpdate update = mock(OrganisationVatUpdate.class);
        OrganisationVat mock = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(mock));
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "parentCustomerCode"))).thenReturn(Optional.empty());

        OrganisationVatView result = organisationVatService.update("organisationId", update);
        assertTrue(result.getError().isPresent());
        assertEquals("PARENT_ORGANISATION_VAT_DO_NOT_EXISTS", result.getError().get().getTitle());

    }

    @Test
    void update_success() {
        OrganisationVatUpdate update = mock(OrganisationVatUpdate.class);
        OrganisationVat parent = mock(OrganisationVat.class);
        OrganisationVat saved = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new OrganisationVat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(saved.getActive()).thenReturn(true);

        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(saved));
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "parentCustomerCode"))).thenReturn(Optional.of(parent));
        when(organisationVatRepository.save(any(OrganisationVat.class)))
            .thenReturn(saved);

        OrganisationVatView result = organisationVatService.update("organisationId", update);

        assertTrue(result.getError().isEmpty());
        assertEquals("customerCode", result.getCustomerCode());
        assertEquals("parentCustomerCode", result.getParentOrganisationVat());
        assertEquals(BigDecimal.ONE.toString(), result.getRate());
        assertEquals("organisationId", result.getOrganisationId());
        assertEquals(true, result.getActive());
    }

    @Test
    void insertVatCodesCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, OrganisationVatUpdate.class)).thenReturn(Either.left(Problem.builder()
                .withTitle("CSV_PARSE_ERROR")
                .withDetail("Error parsing CSV file")
                .build()));

        Either<Problem, List<OrganisationVatView>> response = organisationVatService.insertVatCodesCsv("organisationId", file);

        assertTrue(response.isLeft());
        assertEquals("CSV_PARSE_ERROR", response.getLeft().getTitle());
    }

    @Test
    void insertVatCodesCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        OrganisationVatUpdate update = mock(OrganisationVatUpdate.class);
        List<OrganisationVatUpdate> updates = List.of(update);
        when(csvParser.parseCsv(file, OrganisationVatUpdate.class)).thenReturn(Either.right(updates));
        OrganisationVat parent = mock(OrganisationVat.class);
        OrganisationVat saved = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new OrganisationVat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getParentOrganisationVat()).thenReturn("parentCustomerCode");
        when(saved.getActive()).thenReturn(true);

        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(organisationVatRepository.findById(new OrganisationVat.Id("organisationId", "parentCustomerCode"))).thenReturn(Optional.of(parent));
        when(organisationVatRepository.save(any(OrganisationVat.class)))
                .thenReturn(saved);
        Either<Problem, List<OrganisationVatView>> response = organisationVatService.insertVatCodesCsv("organisationId", file);

        assertTrue(response.isRight());
        assertEquals(updates.size(), response.get().size());
    }
}
