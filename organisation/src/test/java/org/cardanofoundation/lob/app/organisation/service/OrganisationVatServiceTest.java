package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.*;

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationVat;
import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.repository.VatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class OrganisationVatServiceTest {

    @Mock
    private VatRepository vatRepository;
    @Mock
    private CsvParser<VatUpdate> csvParser;

    @InjectMocks
    private OrganisationVatService organisationVatService;

    @Test
    void findByOrganisationAndCodeTest() {
        OrganisationVat.Id id = new OrganisationVat.Id("organisationId", "customerCode");
        OrganisationVat vat = mock(OrganisationVat.class);
        when(vatRepository.findByIdAndActive(id, true)).thenReturn(Optional.of(vat));

        Optional<OrganisationVat> result = organisationVatService.findByOrganisationAndCode("organisationId", "customerCode");

        assertTrue(result.isPresent());
        assertEquals(vat, result.get());
    }

    @Test
    void insert_alreadyExists() {
        VatUpdate vatUpdate = mock(VatUpdate.class);
        OrganisationVat.Id id = new OrganisationVat.Id("organisationId", "customerCode");
        when(vatUpdate.getCustomerCode()).thenReturn("customerCode");
        when(vatRepository.findById(id)).thenReturn(Optional.of(mock(OrganisationVat.class)));

        VatView result = organisationVatService.insert("organisationId", vatUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_VAT_ALREADY_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void insert_countryCodeNotExists() {
        VatUpdate update = mock(VatUpdate.class);

        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CHs");
        when(vatRepository.findById(any())).thenReturn(Optional.empty());

        VatView result = organisationVatService.insert("organisationId", update);

        assertTrue(result.getError().isPresent());
        assertEquals("COUNTRY_CODE_NOT_FOUND", result.getError().get().getTitle());
    }

    @Test
    void insert_success() {
        VatUpdate update = mock(VatUpdate.class);
        OrganisationVat parent = mock(OrganisationVat.class);
        OrganisationVat saved = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new OrganisationVat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getCountryCode()).thenReturn("CH");
        when(saved.getActive()).thenReturn(true);

        when(vatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(vatRepository.save(any(OrganisationVat.class)))
                .thenReturn(saved);

        VatView result = organisationVatService.insert("organisationId", update);

        assertTrue(result.getError().isEmpty());
        assertEquals("customerCode", result.getCustomerCode());
        assertEquals("CH", result.getCountryCode());
        assertEquals(BigDecimal.ONE.toString(), result.getRate());
        assertEquals("organisationId", result.getOrganisationId());
        assertEquals(true, result.getActive());
    }

    @Test
    void update_notExists() {
        VatUpdate vatUpdate = mock(VatUpdate.class);
        OrganisationVat.Id id = new OrganisationVat.Id("organisationId", "customerCode");
        when(vatUpdate.getCustomerCode()).thenReturn("customerCode");
        when(vatRepository.findById(id)).thenReturn(Optional.empty());

        VatView result = organisationVatService.update("organisationId", vatUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_VAT_DO_NOT_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void update_countryCodeNotExists() {
        VatUpdate update = mock(VatUpdate.class);
        OrganisationVat mock = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CHs");
        when(vatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(mock));

        VatView result = organisationVatService.update("organisationId", update);
        assertTrue(result.getError().isPresent());
        assertEquals("COUNTRY_CODE_NOT_FOUND", result.getError().get().getTitle());

    }

    @Test
    void update_success() {
        VatUpdate update = mock(VatUpdate.class);
        OrganisationVat parent = mock(OrganisationVat.class);
        OrganisationVat saved = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new OrganisationVat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getCountryCode()).thenReturn("CH");
        when(saved.getActive()).thenReturn(true);

        when(vatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(saved));
        when(vatRepository.save(any(OrganisationVat.class)))
                .thenReturn(saved);

        VatView result = organisationVatService.update("organisationId", update);

        assertTrue(result.getError().isEmpty());
        assertEquals("customerCode", result.getCustomerCode());
        assertEquals("CH", result.getCountryCode());
        assertEquals(BigDecimal.ONE.toString(), result.getRate());
        assertEquals("organisationId", result.getOrganisationId());
        assertEquals(true, result.getActive());
    }

    @Test
    void insertVatCodesCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, VatUpdate.class)).thenReturn(Either.left(Problem.builder()
                .withTitle("CSV_PARSE_ERROR")
                .withDetail("Error parsing CSV file")
                .build()));

        Either<Problem, List<VatView>> response = organisationVatService.insertVatCodesCsv("organisationId", file);

        assertTrue(response.isLeft());
        assertEquals("CSV_PARSE_ERROR", response.getLeft().getTitle());
    }

    @Test
    void insertVatCodesCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        VatUpdate update = mock(VatUpdate.class);
        List<VatUpdate> updates = List.of(update);
        when(csvParser.parseCsv(file, VatUpdate.class)).thenReturn(Either.right(updates));
        OrganisationVat parent = mock(OrganisationVat.class);
        OrganisationVat saved = mock(OrganisationVat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new OrganisationVat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getCountryCode()).thenReturn("CH");
        when(saved.getActive()).thenReturn(true);

        when(vatRepository.findById(new OrganisationVat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(vatRepository.save(any(OrganisationVat.class)))
                .thenReturn(saved);
        Either<Problem, List<VatView>> response = organisationVatService.insertVatCodesCsv("organisationId", file);

        assertTrue(response.isRight());
        assertEquals(updates.size(), response.get().size());
    }
}
