package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

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

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@ExtendWith(MockitoExtension.class)
class ReferenceCodeServiceTest {

    @Mock
    private ReferenceCodeRepository referenceCodeRepository;

    @Mock
    private OrganisationService organisationService;

    @Mock
    private CsvParser<ReferenceCodeUpdate> csvParser;

    @Mock
    private AccountEventService accountEventService;
    @Mock
    private Validator validator;

    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

    @InjectMocks
    private ReferenceCodeService referenceCodeService;

    private static final String ORG_ID = "org123";
    private static final String REF_CODE = "ref001";

    private ReferenceCode referenceCode;
    private ReferenceCodeView referenceCodeView;
    private ReferenceCodeUpdate referenceCodeUpdate;
    private Organisation mockOrganisation;

    @BeforeEach
    void setUp() {
        referenceCode = ReferenceCode.builder()
                .id(new ReferenceCode.Id(ORG_ID, REF_CODE))
                .name("Test Reference")
                .build();

        referenceCodeView = ReferenceCodeView.fromEntity(referenceCode);
        mockOrganisation = new Organisation(ORG_ID,"testOrg","testCity","testPostCode","testProvince","testAddress","testPhone","testTaxId","IE","00000000",false,false,7305,"ISO_4217:CHF","ISO_4217:CHF","http://testWeb","email@test.com",null);

        referenceCodeUpdate = new ReferenceCodeUpdate(REF_CODE, "Updated Reference",null, true);
    }

    @Test
    void insertReferenceCodeByCsv_parseError() {
        String orgId = "org123";
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, ReferenceCodeUpdate.class)).thenReturn(Either.left(Problem.builder().withTitle("ParseError").build()));

        Either<List<Problem>, List<ReferenceCodeView>> result = referenceCodeService.insertReferenceCodeByCsv(orgId, file);

        assertTrue(result.isLeft());
        assertEquals(1, result.getLeft().size());
        assertEquals("ParseError", result.getLeft().iterator().next().getTitle());
    }

    @Test
    void insertReferenceCodeByCsv_cantFindOrg() {
        String orgId = "org123";
        MultipartFile file = mock(MultipartFile.class);
        ReferenceCodeUpdate refCodeUpdate = new ReferenceCodeUpdate("ref001", "Test Reference", null, true);
        when(csvParser.parseCsv(file, ReferenceCodeUpdate.class)).thenReturn(Either.right(List.of(refCodeUpdate)));

        Errors errors = mock(Errors.class);
        when(validator.validateObject(refCodeUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        Either<List<Problem>, List<ReferenceCodeView>> result = referenceCodeService.insertReferenceCodeByCsv(orgId, file);

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals("Unable to find Organisation by Id: org123", result.get().iterator().next().getError().get().getDetail());
    }

    @Test
    void insertReferencCodeByCsv_success() {
        String orgId = "org123";
        MultipartFile file = mock(MultipartFile.class);
        ReferenceCodeUpdate refCodeUpdate = new ReferenceCodeUpdate(REF_CODE, "Test Reference", null, true);

        Errors errors = mock(Errors.class);
        when(validator.validateObject(refCodeUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of());

        when(csvParser.parseCsv(file, ReferenceCodeUpdate.class)).thenReturn(Either.right(List.of(refCodeUpdate)));
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(orgId)).thenReturn(Optional.of(mockOrganisation));
        when(referenceCodeRepository.save(any(ReferenceCode.class))).thenReturn(referenceCode);

        Either<List<Problem>, List<ReferenceCodeView>> result = referenceCodeService.insertReferenceCodeByCsv(orgId, file);
        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
    }

    @Test
    void insertReferencCodeByCsv_validationError() {
        String orgId = "org123";
        MultipartFile file = mock(MultipartFile.class);
        ReferenceCodeUpdate refCodeUpdate = new ReferenceCodeUpdate(REF_CODE, "Test Reference", null, true);

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(refCodeUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        when(csvParser.parseCsv(file, ReferenceCodeUpdate.class)).thenReturn(Either.right(List.of(refCodeUpdate)));
        Either<List<Problem>, List<ReferenceCodeView>> result = referenceCodeService.insertReferenceCodeByCsv(orgId, file);
        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals("Default Message", result.get().iterator().next().getError().get().getDetail());
    }

    @Test
    void testGetAllReferenceCodes() {
        when(referenceCodeRepository.findAllByOrgId(ORG_ID, null, null, null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(referenceCode)));

        Either<Problem, List<ReferenceCodeView>> result = referenceCodeService.getAllReferenceCodes(ORG_ID, null, null, null, null, Pageable.unpaged());

        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertEquals(referenceCodeView.getReferenceCode(), result.get().getFirst().getReferenceCode());
        assertEquals(referenceCodeView.getDescription(), result.get().getFirst().getDescription());
        verify(referenceCodeRepository).findAllByOrgId(ORG_ID, null, null, null, null, Pageable.unpaged());
    }

    @Test
    void testGetReferenceCode_Found() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.of(referenceCode));

        Optional<ReferenceCodeView> result = referenceCodeService.getReferenceCode(ORG_ID, REF_CODE);

        assertTrue(result.isPresent());
        assertEquals(referenceCodeView.getReferenceCode(), result.get().getReferenceCode());
        assertEquals(referenceCodeView.getDescription(), result.get().getDescription());
        verify(referenceCodeRepository).findByOrgIdAndReferenceCode(ORG_ID, REF_CODE);
    }

    @Test
    void testGetReferenceCode_NotFound() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.empty());

        Optional<ReferenceCodeView> result = referenceCodeService.getReferenceCode(ORG_ID, REF_CODE);

        assertTrue(result.isEmpty());
        verify(referenceCodeRepository).findByOrgIdAndReferenceCode(ORG_ID, REF_CODE);
    }

    @Test
    void testUpdateReferenceCode_UpsertNoOrg() {
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.empty());
        ReferenceCodeView result = referenceCodeService.updateReferenceCode(ORG_ID, referenceCodeUpdate);

        assertTrue(result.getError().isPresent());

    }

    @Test
    void testUpdateReferenceCode_InsertNew() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        referenceCode.setName("Updated Reference");
        ReferenceCodeView result = referenceCodeService.updateReferenceCode(ORG_ID, referenceCodeUpdate);

        assertTrue(result.getError().isPresent());
        assertEquals("REFERENCE_CODE_NOT_FOUND", result.getError().get().getTitle());
        //verify(referenceCodeRepository).save(any(ReferenceCode.class));
    }

    @Test
    void testUpdateReferenceCode_UpdateExisting() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.of(referenceCode));
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        referenceCode.setParent(new ReferenceCode(new ReferenceCode.Id(ORG_ID, "0102"), null, "Parent Reference", "2", true));
        when(referenceCodeRepository.save(any(ReferenceCode.class))).thenReturn(referenceCode);

        ReferenceCodeView result = referenceCodeService.updateReferenceCode(ORG_ID, referenceCodeUpdate);

        assertTrue(result.getError().isEmpty());
        assertEquals("Updated Reference", result.getDescription());
        assertEquals("0102", result.getParent().getReferenceCode());
        verify(referenceCodeRepository).save(referenceCode);
    }

    @Test
    void testUpdateReferenceCode_UpdateExistingNotFindParentCode() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, "0102")).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        referenceCodeUpdate.setParentReferenceCode("0102");
        ReferenceCodeView result = referenceCodeService.updateReferenceCode(ORG_ID, referenceCodeUpdate);

        assertTrue(result.getError().isPresent());
    }

    // NEW

    @Test
    void testInsertReferenceCode_UpsertNoOrg() {
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.empty());
        ReferenceCodeView result = referenceCodeService.insertReferenceCode(ORG_ID, referenceCodeUpdate, false);

        assertTrue(result.getError().isPresent());

    }

    @Test
    void testInsertReferenceCode_InsertNew() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        referenceCode.setName("Updated Reference");
        when(referenceCodeRepository.save(any(ReferenceCode.class))).thenReturn(referenceCode);
        ReferenceCodeView result = referenceCodeService.insertReferenceCode(ORG_ID, referenceCodeUpdate, false);


        assertEquals("Updated Reference", result.getDescription());
        verify(referenceCodeRepository).save(any(ReferenceCode.class));

    }

    @Test
    void testInsertReferenceCode_UpdateExisting() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, REF_CODE)).thenReturn(Optional.of(referenceCode));
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));
        referenceCode.setParent(new ReferenceCode(new ReferenceCode.Id(ORG_ID, "0102"), null, "Parent Reference", "2", true));
        //when(referenceCodeRepository.save(any(ReferenceCode.class))).thenReturn(referenceCode);

        ReferenceCodeView result = referenceCodeService.insertReferenceCode(ORG_ID, referenceCodeUpdate, false);

        assertTrue(result.getError().isPresent());
        assertEquals("REFERENCE_CODE_ALREADY_EXIST", result.getError().get().getTitle());
        //verify(referenceCodeRepository).save(any(ReferenceCode.class));
    }

    @Test
    void testInsertReferenceCode_UpdateExistingNotFindParentCode() {
        when(referenceCodeRepository.findByOrgIdAndReferenceCode(ORG_ID, "0102")).thenReturn(Optional.empty());
        when(organisationService.findById(ORG_ID)).thenReturn(Optional.of(mockOrganisation));

        referenceCodeUpdate.setParentReferenceCode("0102");
        ReferenceCodeView result = referenceCodeService.insertReferenceCode(ORG_ID, referenceCodeUpdate, false);

        assertTrue(result.getError().isPresent());
    }

}
