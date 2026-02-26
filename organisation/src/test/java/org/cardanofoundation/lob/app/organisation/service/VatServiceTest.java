package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.Vat;
import org.cardanofoundation.lob.app.organisation.domain.request.VatUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.VatView;
import org.cardanofoundation.lob.app.organisation.repository.VatRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class VatServiceTest {

    @Mock
    private VatRepository vatRepository;
    @Mock
    private CsvParser<VatUpdate> csvParser;
    @Mock
    private Validator validator;

    @InjectMocks
    private VatService vatService;

    @Test
    void findByOrganisationAndCodeTest() {
        Vat.Id id = new Vat.Id("organisationId", "customerCode");
        Vat vat = mock(Vat.class);
        when(vatRepository.findByIdAndActive(id, true)).thenReturn(Optional.of(vat));

        Optional<Vat> result = vatService.findByOrganisationAndCode("organisationId", "customerCode");

        assertTrue(result.isPresent());
        assertEquals(vat, result.get());
    }

    @Test
    void insert_alreadyExists() {
        VatUpdate vatUpdate = mock(VatUpdate.class);
        Vat.Id id = new Vat.Id("organisationId", "customerCode");
        when(vatUpdate.getCustomerCode()).thenReturn("customerCode");
        when(vatRepository.findById(id)).thenReturn(Optional.of(mock(Vat.class)));

        VatView result = vatService.insert("organisationId", vatUpdate, false);

        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_VAT_ALREADY_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void insert_countryCodeNotExists() {
        VatUpdate update = mock(VatUpdate.class);

        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CHs");
        when(vatRepository.findById(any())).thenReturn(Optional.empty());

        VatView result = vatService.insert("organisationId", update, false);

        assertTrue(result.getError().isPresent());
        assertEquals("COUNTRY_CODE_NOT_FOUND", result.getError().get().getTitle());
    }

    @Test
    void insert_rateMustNotBeNegative() {
        VatUpdate update = mock(VatUpdate.class);

        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.valueOf(-1));
        when(vatRepository.findById(any())).thenReturn(Optional.empty());

        VatView result = vatService.insert("organisationId", update, false);

        assertTrue(result.getError().isPresent());
        assertEquals("VAT_RATE_CANNOT_BE_NEGATIVE", result.getError().get().getTitle());
    }

    @Test
    void insert_success() {
        VatUpdate update = mock(VatUpdate.class);
        Vat saved = mock(Vat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new Vat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getCountryCode()).thenReturn("CH");
        when(saved.getActive()).thenReturn(true);

        when(vatRepository.findById(new Vat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(vatRepository.save(any(Vat.class)))
                .thenReturn(saved);

        VatView result = vatService.insert("organisationId", update, false);

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
        Vat.Id id = new Vat.Id("organisationId", "customerCode");
        when(vatUpdate.getCustomerCode()).thenReturn("customerCode");
        when(vatRepository.findById(id)).thenReturn(Optional.empty());

        VatView result = vatService.update("organisationId", vatUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("ORGANISATION_VAT_DO_NOT_EXISTS", result.getError().get().getTitle());
    }

    @Test
    void update_countryCodeNotExists() {
        VatUpdate update = mock(VatUpdate.class);
        Vat mock = mock(Vat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CHs");
        when(vatRepository.findById(new Vat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(mock));

        VatView result = vatService.update("organisationId", update);
        assertTrue(result.getError().isPresent());
        assertEquals("COUNTRY_CODE_NOT_FOUND", result.getError().get().getTitle());

    }

    @Test
    void update_rateMustNotBeNegative() {
        VatUpdate update = mock(VatUpdate.class);
        Vat mock = mock(Vat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.valueOf(-1));
        when(vatRepository.findById(new Vat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(mock));

        VatView result = vatService.update("organisationId", update);
        assertTrue(result.getError().isPresent());
        assertEquals("VAT_RATE_CANNOT_BE_NEGATIVE", result.getError().get().getTitle());

    }

    @Test
    void update_success() {
        VatUpdate update = mock(VatUpdate.class);
        Vat saved = mock(Vat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new Vat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getCountryCode()).thenReturn("CH");
        when(saved.getActive()).thenReturn(true);

        when(vatRepository.findById(new Vat.Id("organisationId", "customerCode"))).thenReturn(Optional.of(saved));
        when(vatRepository.save(any(Vat.class)))
                .thenReturn(saved);

        VatView result = vatService.update("organisationId", update);

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
        when(csvParser.parseCsv(file, VatUpdate.class)).thenReturn(Either.left(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV_PARSE_ERROR")));

        Either<ProblemDetail, List<VatView>> response = vatService.insertVatCodesCsv("organisationId", file);

        assertTrue(response.isLeft());
        assertEquals("CSV_PARSE_ERROR", response.getLeft().getDetail());
    }

    @Test
    void insertVatCodesCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        VatUpdate update = mock(VatUpdate.class);
        List<VatUpdate> updates = List.of(update);
        when(csvParser.parseCsv(file, VatUpdate.class)).thenReturn(Either.right(updates));

        Errors errors = mock(Errors.class);
        when(validator.validateObject(update)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        Vat saved = mock(Vat.class);
        when(update.getCustomerCode()).thenReturn("customerCode");
        when(update.getCountryCode()).thenReturn("CH");
        when(update.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getId()).thenReturn(new Vat.Id("organisationId", "customerCode"));
        when(saved.getRate()).thenReturn(BigDecimal.ONE);
        when(saved.getCountryCode()).thenReturn("CH");
        when(saved.getActive()).thenReturn(true);

        when(vatRepository.findById(new Vat.Id("organisationId", "customerCode"))).thenReturn(Optional.empty());
        when(vatRepository.save(any(Vat.class)))
                .thenReturn(saved);
        Either<ProblemDetail, List<VatView>> response = vatService.insertVatCodesCsv("organisationId", file);

        assertTrue(response.isRight());
        assertEquals(updates.size(), response.get().size());
    }

    @Test
    void insertVatCodesCsv_validationError() {
        MultipartFile file = mock(MultipartFile.class);
        VatUpdate update = mock(VatUpdate.class);
        List<VatUpdate> updates = List.of(update);
        when(csvParser.parseCsv(file, VatUpdate.class)).thenReturn(Either.right(updates));

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(update)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        Either<ProblemDetail, List<VatView>> response = vatService.insertVatCodesCsv("organisationId", file);
        assertTrue(response.isRight());
        assertEquals(1, response.get().size());
        assertEquals("Default Message", response.get().get(0).getError().get().getDetail());
    }

    @Test
    void shouldWriteCurrenciesToCsv() throws Exception {
        // given

        String orgId = "org123";
        Vat vat1 = mock(Vat.class);
        when(vat1.getId()).thenReturn(new Vat.Id(orgId, "vat1"));
        when(vat1.getRate()).thenReturn(BigDecimal.ONE);
        when(vat1.getDescription()).thenReturn("description1");
        when(vat1.getCountryCode()).thenReturn("DE");
        when(vat1.getActive()).thenReturn(true);
        Vat vat2 = mock(Vat.class);
        when(vat2.getId()).thenReturn(new Vat.Id(orgId, "vat2"));
        when(vat2.getRate()).thenReturn(BigDecimal.TWO);
        when(vat2.getDescription()).thenReturn("description2");
        when(vat2.getCountryCode()).thenReturn("DE");
        when(vat2.getActive()).thenReturn(true);

        Page<Vat> page = new PageImpl<>(List.of(vat1, vat2));

        when(vatRepository.findAllByOrganisationId(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(page);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // when
        vatService.downloadCsv(orgId, null, null, null, null, null, null,outputStream);

        // then
        String csv = outputStream.toString(StandardCharsets.UTF_8);

        String[] lines = csv.split("\\R");

        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("Code,Rate,Description,Country,Active");
        assertThat(lines[1]).isEqualTo("vat1,1,description1,DE,true");
        assertThat(lines[2]).isEqualTo("vat2,2,description2,DE,true");

    }
}
