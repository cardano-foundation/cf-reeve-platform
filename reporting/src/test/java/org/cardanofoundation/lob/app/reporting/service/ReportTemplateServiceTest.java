package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFilter;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateListResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleTermDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateValidationRuleEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ValidationRuleTermEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ComparisonOperator;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.model.enums.TermOperation;
import org.cardanofoundation.lob.app.reporting.model.enums.TermSide;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTemplateTypeValidator;

@ExtendWith(MockitoExtension.class)
class ReportTemplateServiceTest {

    @Mock
    private ReportTemplateRepository reportTemplateRepository;
    @Mock
    private ReportTemplateMapper reportTemplateMapper;
    @Mock
    private ReportingRepository reportingRepository;
    @Mock
    private Validator validator;
    @Mock
    private ReportTemplateTypeValidator reportTemplateTypeValidator;
    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;
    @Mock
    private ReportingService reportingService;

    @InjectMocks
    private ReportTemplateService reportTemplateService;

    private ReportTemplateDto templateDto;
    private ReportTemplateEntity templateEntity;
    private ReportTemplateResponseDto templateResponseDto;

    @BeforeEach
    void setUp() {
        // Setup test data
        templateDto = new ReportTemplateDto();
        templateDto.setId("abc");
        templateDto.setName("Test Template");
        templateDto.setOrganisationId("org123");
        templateDto.setReportTemplateType("BALANCE_SHEET");
        templateDto.setDataMode("SYSTEM");
        templateDto.setFields(new ArrayList<>());
        templateDto.setValidationRules(new ArrayList<>());

        templateEntity = new ReportTemplateEntity();
        templateEntity.setId("abc");
        templateEntity.setOrganisationId("org123");
        templateEntity.setName("Test Template");
        templateEntity.setVer(1L);
        templateEntity.setReportTemplateType(ReportTemplateType.BALANCE_SHEET);
        templateEntity.setFields(new ArrayList<>());

        templateResponseDto = new ReportTemplateResponseDto();
        templateResponseDto.setId("abc");
        templateResponseDto.setName("Test Template");
    }

    @Test
    void create_NewTemplate_Success() {

        // Given
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.empty());
        when(reportTemplateMapper.toEntity(eq(templateDto), isNull())).thenReturn(templateEntity);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(templateEntity);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.right(null));
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals("Test Template", result.get().getName());
        verify(reportTemplateRepository).save(templateEntity);
    }

    @Test
    void create_NewTemplate_InvalidDataMode() {
        templateDto.setDataMode("WRONG_DATAMODE");
        // Given
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("INVALID_DATA_MODE", result.getLeft().getTitle());

        templateDto.setDataMode("");
        result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("DATA_MODE_MISSING", result.getLeft().getTitle());
    }

    @Test
    void create_NewTemplate_INVALID_FIELD_MAPPINGS() {
        templateDto.setDataMode("SYSTEM");
        templateDto.setFields(List.of(ReportTemplateFieldDto.builder().childFields(List.of()).accounts(Set.of()).build()));
        // Given
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("INVALID_FIELD_MAPPINGS", result.getLeft().getTitle());

        templateDto.setDataMode("USER");
        templateDto.setFields(List.of(ReportTemplateFieldDto.builder().accounts(Set.of("12345")).build()));
        result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("INVALID_FIELD_MAPPINGS", result.getLeft().getTitle());
    }

    @Test
    void createNewTemplate_validateForbiddenCharactersFail() {

        Errors errors = mock(Errors.class);
        templateDto.setDataMode(DataMode.USER.name());
        templateDto.setFields(List.of(ReportTemplateFieldDto.builder().accounts(Set.of()).childFields(List.of()).fieldName("name$").build()));

        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_FIELD_NAME", reportTemplateResponseDtos.getLeft().getTitle());
    }

    @Test
    void createNewTemplate_validateAccountsFailed() {

        Errors errors = mock(Errors.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                            .build()
                )
        );
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of());
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_ACCOUNTS", reportTemplateResponseDtos.getLeft().getTitle());
    }

    @Test
    void createNewTemplate_validateValidationRules() {

        Errors errors = mock(Errors.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("")
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Validation rule must have a name", reportTemplateResponseDtos.getLeft().getDetail());

        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("INVALID_OPERATOR")
                        .build()
        ));
    }

    @Test
    void createNewTemplate_validateValidationInvalidOperator() {

        Errors errors = mock(Errors.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("INVALID_OPERATOR")
                        .leftSideTerms(List.of(mock(ValidationRuleTermDto.class)))
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Invalid comparison operator: INVALID_OPERATOR. Must be one of: GREATER_THAN_OR_EQUAL, EQUAL, LESS_THAN_OR_EQUAL", reportTemplateResponseDtos.getLeft().getDetail());
    }

    @Test
    void createNewTemplate_validateValidationOneSideIsEmpty() {

        Errors errors = mock(Errors.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Validation rule 'valid1' must have at least one term on the left side", reportTemplateResponseDtos.getLeft().getDetail());

        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .leftSideTerms(List.of(mock(ValidationRuleTermDto.class)))
                        .build()
        ));

        reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Validation rule 'valid1' must have at least one term on the right side", reportTemplateResponseDtos.getLeft().getDetail());
    }

    @Test
    void createNewTemplate_validateValidationFieldNameIsEmpty() {

        Errors errors = mock(Errors.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .leftSideTerms(List.of(mock(ValidationRuleTermDto.class)))
                        .rightSideTerms(List.of(mock(ValidationRuleTermDto.class)))
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Validation Rule term must have names.", reportTemplateResponseDtos.getLeft().getDetail());
    }

    @Test
    void createNewTemplate_validateValidationFieldNotExists() {

        Errors errors = mock(Errors.class);
        ValidationRuleTermDto ruleTermDto = mock(ValidationRuleTermDto.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .leftSideTerms(List.of(ruleTermDto))
                        .rightSideTerms(List.of(ruleTermDto))
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(ruleTermDto.getFieldName()).thenReturn("ruleTermDto");
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Validation rule 'valid1' references field names '[ruleTermDto]' which do not exist in the template", reportTemplateResponseDtos.getLeft().getDetail());
    }

    @Test
    void createNewTemplate_validateValidationWrongOperator() {

        Errors errors = mock(Errors.class);
        ValidationRuleTermDto ruleTermDto = mock(ValidationRuleTermDto.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .leftSideTerms(List.of(ruleTermDto))
                        .rightSideTerms(List.of(ruleTermDto))
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(ruleTermDto.getFieldName()).thenReturn("name");
        when(ruleTermDto.getOperation()).thenReturn("INVALID_OPERATION");
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Invalid term operation: INVALID_OPERATION. Must be one of: ADD, SUBTRACT", reportTemplateResponseDtos.getLeft().getDetail());
    }

    @Test
    void createNewTemplate_validateValidationDuplicateName() {

        Errors errors = mock(Errors.class);
        ValidationRuleTermDto ruleTermDto = mock(ValidationRuleTermDto.class);
        templateDto.setFields(
                List.of(
                        ReportTemplateFieldDto.builder()
                                .accounts(Set.of("acc1"))
                                .childFields(List.of())
                                .fieldName("name")
                                .build()
                )
        );
        templateDto.setValidationRules(List.of(
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .leftSideTerms(List.of(ruleTermDto))
                        .rightSideTerms(List.of(ruleTermDto))
                        .build(),
                ValidationRuleDto.builder()
                        .name("valid1")
                        .operator("GREATER_THAN_OR_EQUAL")
                        .leftSideTerms(List.of(ruleTermDto))
                        .rightSideTerms(List.of(ruleTermDto))
                        .build()
        ));
        ChartOfAccount mock = mock(ChartOfAccount.class);
        when(ruleTermDto.getFieldName()).thenReturn("name");
        when(ruleTermDto.getOperation()).thenReturn("ADD");
        when(mock.getId()).thenReturn(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1"));
        when(chartOfAccountRepository.findAllById(eq(List.of(new ChartOfAccount.Id(templateDto.getOrganisationId(), "acc1")))))
                .thenReturn(List.of(mock));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

        assertTrue(reportTemplateResponseDtos.isLeft());
        assertEquals("INVALID_VALIDATION_RULE", reportTemplateResponseDtos.getLeft().getTitle());
        assertEquals("Duplicate validation rule name found: 'valid1'. Each validation rule must have a unique name.", reportTemplateResponseDtos.getLeft().getDetail());
    }

    @Test
    void create_NewTemplate_TypeValidatorError() {

        // Given
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.empty());
        when(reportTemplateMapper.toEntity(eq(templateDto), isNull())).thenReturn(templateEntity);

        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
    }

    @Test
    void create_NewTemplate_ValidationError() {

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(any())).thenReturn(errors);
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Default Message", result.getLeft().getDetail());
    }

    @Test
    void create_NewTemplate_ValidationErrorChilds() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto fieldDto = mock(ReportTemplateFieldDto.class);
        Errors errors = mock(Errors.class);
        Errors errorsDto = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);

        when(dto.getFields()).thenReturn(List.of(fieldDto));
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(validator.validateObject(any(ReportTemplateDto.class))).thenReturn(errorsDto);
        when(validator.validateObject(any(ReportTemplateFieldDto.class))).thenReturn(errors);
        when(errorsDto.getAllErrors()).thenReturn(List.of());
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Default Message", result.getLeft().getDetail());
    }

    @Test
    void create_NewTemplate_duplicateNames() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDtoField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto fieldDto1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto fieldDto2 = mock(ReportTemplateFieldDto.class);
        Errors errors = mock(Errors.class);

        when(dto.getFields()).thenReturn(List.of(parentDtoField));
        when(dto.getDataMode()).thenReturn("USER");
        when(parentDtoField.getChildFields()).thenReturn(List.of(fieldDto1, fieldDto2));
        when(parentDtoField.getFieldName()).thenReturn("parentName");
        when(fieldDto1.getFieldName()).thenReturn("sameName");
        when(fieldDto2.getFieldName()).thenReturn("sameName");
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Duplicate field name 'sameName' under parent 'parentName'. Field names must be unique within the same parent.", result.getLeft().getDetail());
    }

    @Test
    void create_TemplateAlreadyExists_ReturnsConflict() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);

        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.of(existing));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Already Exists", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void update_ExistingTemplateWithoutReports_UpdatesInPlace() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);
        existing.setName(templateDto.getName());
        existing.setDataMode(DataMode.valueOf(templateDto.getDataMode()));
        existing.setReportTemplateType(ReportTemplateType.valueOf(templateDto.getReportTemplateType()));
        existing.setValidationRules(List.of());

        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());
        when(reportTemplateMapper.toEntity(eq(templateDto), eq(existing))).thenReturn(existing);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(existing);
        when(reportTemplateMapper.toResponseDto(existing)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.right(null));
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        verify(reportTemplateMapper).toEntity(eq(templateDto), eq(existing));
        verify(reportTemplateRepository).save(existing);
    }

    @Test
    void update_CreateNewVersionWhenReportsExist() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);
        existing.setName(templateDto.getName());
        existing.setDataMode(DataMode.valueOf(templateDto.getDataMode()));
        existing.setReportTemplateType(ReportTemplateType.valueOf(templateDto.getReportTemplateType()));
        existing.setFields(List.of(new ReportTemplateFieldEntity()));
        existing.setValidationRules(List.of());
        templateDto.setValidationRules(List.of());


        ReportEntity report = new ReportEntity();
        report.setId("abc");

        ReportTemplateEntity newVersion = new ReportTemplateEntity();
        newVersion.setName(templateDto.getName());
        newVersion.setDataMode(DataMode.valueOf(templateDto.getDataMode()));
        newVersion.setReportTemplateType(ReportTemplateType.valueOf(templateDto.getReportTemplateType()));

        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(report));
        when(reportTemplateMapper.toEntity(eq(templateDto), isNull())).thenReturn(newVersion);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(newVersion);
        when(reportTemplateMapper.toResponseDto(newVersion)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.right(null));

        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals(2L, newVersion.getVer());
        assertFalse(existing.isActive());
        verify(reportTemplateRepository).save(newVersion);
        verify(reportTemplateRepository).save(existing);

    }

    @Test
    void update_keepVersionValidationRulesMatch() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);
        existing.setDataMode(DataMode.USER);
        existing.setName(templateDto.getName());
        existing.setReportTemplateType(ReportTemplateType.valueOf(templateDto.getReportTemplateType()));
        ReportTemplateFieldEntity field = new ReportTemplateFieldEntity();
        field.setName("field1");
        existing.setFields(List.of(field));
        ReportTemplateValidationRuleEntity ruleEntity = ReportTemplateValidationRuleEntity.builder()
                .name("rule1")
                .operator(ComparisonOperator.EQUAL)
                .reportTemplate(existing)
                .active(true)
                .terms(List.of(ValidationRuleTermEntity.builder()
                        .side(TermSide.LEFT)
                        .field(field)
                        .operation(TermOperation.ADD)
                        .build(),
                        ValidationRuleTermEntity.builder()
                                .side(TermSide.RIGHT)
                                .field(field)
                                .operation(TermOperation.ADD)
                                .build()))
                .build();
        existing.setValidationRules(List.of(ruleEntity));
        templateDto.setFields(List.of(ReportTemplateFieldDto.builder()
                .fieldName("field1")
                        .accounts(Set.of())
                .childFields(List.of())
                .dateRange(ReportFieldDateRange.PERIOD)
                .build()));
        templateDto.setDataMode("USER");
        templateDto.setValidationRules(List.of(ValidationRuleDto.builder()
                        .name("rule2") // Name doesn'T affect the hash
                        .operator("EQUAL")
                        .leftSideTerms(List.of(ValidationRuleTermDto.builder()
                                .operation("ADD")
                                .fieldName("field1").build()))
                        .rightSideTerms(List.of(ValidationRuleTermDto.builder()
                                        .operation("ADD")
                                        .fieldName("field1").build()))
                .build()));

        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());
        when(reportTemplateMapper.toEntity(eq(templateDto), eq(existing))).thenReturn(existing);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(existing);
        when(reportTemplateMapper.toResponseDto(existing)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.right(null));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(mock(ReportEntity.class)));
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals(1L, existing.getVer());
        verify(reportTemplateMapper).toEntity(eq(templateDto), eq(existing));
        verify(reportTemplateRepository).save(existing);

    }

    @Test
    void update_updateVersionValidationRulesDontMatch() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);
        existing.setDataMode(DataMode.USER);
        existing.setName(templateDto.getName());
        existing.setReportTemplateType(ReportTemplateType.valueOf(templateDto.getReportTemplateType()));
        ReportTemplateFieldEntity field = new ReportTemplateFieldEntity();
        field.setName("field1");
        existing.setFields(List.of(field));
        ReportTemplateValidationRuleEntity ruleEntity = ReportTemplateValidationRuleEntity.builder()
                .name("rule1")
                .operator(ComparisonOperator.EQUAL)
                .reportTemplate(existing)
                .active(true)
                .terms(List.of(ValidationRuleTermEntity.builder()
                                .side(TermSide.LEFT)
                                .field(field)
                                .operation(TermOperation.ADD)
                                .build(),
                        ValidationRuleTermEntity.builder()
                                .side(TermSide.RIGHT)
                                .field(field)
                                .operation(TermOperation.ADD)
                                .build()))
                .build();
        existing.setValidationRules(List.of(ruleEntity));
        templateDto.setFields(List.of(ReportTemplateFieldDto.builder()
                .fieldName("field1")
                .accounts(Set.of())
                .childFields(List.of())
                .dateRange(ReportFieldDateRange.PERIOD)
                .build()));
        templateDto.setDataMode("USER");
        templateDto.setValidationRules(List.of(ValidationRuleDto.builder()
                .name("rule2") // Name doesn'T affect the hash
                .operator("EQUAL")
                .leftSideTerms(List.of(ValidationRuleTermDto.builder()
                        .operation("SUBTRACT")
                        .fieldName("field1").build()))
                .rightSideTerms(List.of(ValidationRuleTermDto.builder()
                        .operation("ADD")
                        .fieldName("field1").build()))
                .build()));

        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());
        when(reportTemplateMapper.toEntity(any(), any())).thenReturn(existing);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(existing);
        when(reportTemplateMapper.toResponseDto(existing)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.right(null));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(mock(ReportEntity.class)));
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals(2L, existing.getVer());
        verify(reportTemplateRepository).save(existing);
    }

    @Test
    void update_updateVersionValidationRuleDeleted() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setId("abc");
        existing.setVer(1L);
        existing.setDataMode(DataMode.USER);
        existing.setName(templateDto.getName());
        existing.setReportTemplateType(ReportTemplateType.valueOf(templateDto.getReportTemplateType()));
        ReportTemplateFieldEntity field = new ReportTemplateFieldEntity();
        field.setName("field1");
        existing.setFields(List.of(field));
        ReportTemplateValidationRuleEntity ruleEntity = ReportTemplateValidationRuleEntity.builder()
                .name("rule1")
                .operator(ComparisonOperator.EQUAL)
                .reportTemplate(existing)
                .active(true)
                .terms(List.of(ValidationRuleTermEntity.builder()
                                .side(TermSide.LEFT)
                                .field(field)
                                .operation(TermOperation.ADD)
                                .build(),
                        ValidationRuleTermEntity.builder()
                                .side(TermSide.RIGHT)
                                .field(field)
                                .operation(TermOperation.ADD)
                                .build()))
                .build();
        existing.setValidationRules(List.of(ruleEntity));
        templateDto.setFields(List.of(ReportTemplateFieldDto.builder()
                .fieldName("field1")
                .accounts(Set.of())
                .childFields(List.of())
                .dateRange(ReportFieldDateRange.PERIOD)
                .build()));
        templateDto.setDataMode("USER");
        templateDto.setValidationRules(List.of());

        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(existing));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());
        when(reportTemplateMapper.toEntity(any(), any())).thenReturn(existing);
        when(reportTemplateRepository.save(any(ReportTemplateEntity.class))).thenReturn(existing);
        when(reportTemplateMapper.toResponseDto(existing)).thenReturn(templateResponseDto);
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.right(null));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(mock(ReportEntity.class)));
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals(2L, existing.getVer());
        verify(reportTemplateRepository).save(existing);
    }

    @Test
    void update_CreateNewVersionParameterChangeNotAllowed() {
        // Given
        ReportTemplateEntity existing = new ReportTemplateEntity();
        existing.setName("name");
        existing.setReportTemplateType(ReportTemplateType.CUSTOM);
        existing.setDataMode(DataMode.SYSTEM);

        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(existing));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        templateDto.setName("name1");
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);
        assertTrue(result.isLeft());
        assertEquals("NAME_CHANGE_NOT_ALLOWED", result.getLeft().getTitle());

        templateDto.setName("name");
        templateDto.setDataMode("USER");
        result = reportTemplateService.update(templateDto);
        assertTrue(result.isLeft());
        assertEquals("DATA_MODE_CHANGE_NOT_ALLOWED", result.getLeft().getTitle());

        templateDto.setDataMode("SYSTEM");
        templateDto.setReportTemplateType("BALANCE_SHEET");
        result = reportTemplateService.update(templateDto);
        assertTrue(result.isLeft());
        assertEquals("REPORT_TEMPLATE_TYPE_CHANGE_NOT_ALLOWED", result.getLeft().getTitle());

    }

    @Test
    void update_TemplateNotFound_ReturnsNotFound() {
        // Given
        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.empty());
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        Either<ProblemDetail, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Not Found", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void delete_Success() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());

        // When
        Either<ProblemDetail, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isRight());
        verify(reportTemplateRepository).deleteById("abc");
    }

    @Test
    void delete_TemplateNotFound() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Either<ProblemDetail, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isLeft());
        assertEquals("TEMPLATE_NOT_FOUND", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).deleteById(anyString());
    }

    @Test
    void delete_TemplateHasAssociatedReports() {
        // Given
        ReportEntity report = new ReportEntity();
        report.setId("abc");

        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(List.of(report));

        // When
        Either<ProblemDetail, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isLeft());
        assertEquals("TEMPLATE_IN_USE", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).deleteById(anyString());
    }

    @Test
    void findById_Success() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        Optional<ReportTemplateResponseDto> result = reportTemplateService.findById("abc");
        // Then
        assertTrue(result.isPresent());
        assertEquals("Test Template", result.get().getName());
    }

    @Test
    void findById_NotFound() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Optional<ReportTemplateResponseDto> result = reportTemplateService.findById("abc");

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void findByOrganisationId_Success() {
        // Given
        List<ReportTemplateEntity> templates = List.of(templateEntity);
        when(reportTemplateRepository.findByOrganisationId("org123")).thenReturn(templates);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        List<ReportTemplateResponseDto> result = reportTemplateService.findByOrganisationId("org123");

        // Then
        assertEquals(1, result.size());
        assertEquals("Test Template", result.get(0).getName());
    }

    @Test
    void existsByOrganisationIdAndName_True() {
        // Given
        when(reportTemplateRepository.existsByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(true);

        // When
        boolean result = reportTemplateService.existsByOrganisationIdAndName("org123", "Test Template");

        // Then
        assertTrue(result);
    }

    @Test
    void existsByOrganisationIdAndName_False() {
        // Given
        when(reportTemplateRepository.existsByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(false);

        // When
        boolean result = reportTemplateService.existsByOrganisationIdAndName("org123", "Test Template");

        // Then
        assertFalse(result);
    }

    // -------------------------------------------------------------------------
    // findAll
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void findAll_noFilters_returnsAllTemplates() {
        // Given
        Page<ReportTemplateEntity> page = mock(Page.class);
        when(page.stream()).thenReturn(Stream.of(templateEntity));
        when(page.getTotalElements()).thenReturn(1L);
        when(page.getTotalPages()).thenReturn(1);
        Pageable pageable = PageRequest.of(0, 10);
        when(reportTemplateRepository.findAllFiltered(
                eq("org123"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        ReportTemplateListResponseDto result = reportTemplateService.findAll(
                "org123", new ReportTemplateFilter(null, null, null, null, null, null), pageable);

        // Then
        assertEquals(1, result.getTemplates().size());
        assertEquals(templateResponseDto, result.getTemplates().getFirst());
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getTotalPages());
        verify(reportTemplateRepository).findAllFiltered(
                eq("org123"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_withDateFilters_passesFiltersToRepository() {
        // Given
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2026, 12, 31);

        Page<ReportTemplateEntity> page = mock(Page.class);
        when(page.stream()).thenReturn(Stream.of(templateEntity));
        when(page.getTotalElements()).thenReturn(1L);
        when(page.getTotalPages()).thenReturn(1);
        Pageable pageable = PageRequest.of(0, 10);
        when(reportTemplateRepository.findAllFiltered(
                eq("org123"), isNull(), isNull(), isNull(), isNull(), eq(dateFrom), eq(dateTo), eq(pageable)))
                .thenReturn(page);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        ReportTemplateListResponseDto result = reportTemplateService.findAll(
                "org123", new ReportTemplateFilter(null, null, null, null, dateFrom, dateTo), pageable);

        // Then
        assertEquals(1, result.getTemplates().size());
        verify(reportTemplateRepository).findAllFiltered(
                eq("org123"), isNull(), isNull(), isNull(), isNull(), eq(dateFrom), eq(dateTo), eq(pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_emptyPage_returnsEmptyList() {
        // Given
        Page<ReportTemplateEntity> page = mock(Page.class);
        when(page.stream()).thenReturn(Stream.empty());
        when(page.getTotalElements()).thenReturn(0L);
        when(page.getTotalPages()).thenReturn(0);
        Pageable pageable = PageRequest.of(0, 10);
        when(reportTemplateRepository.findAllFiltered(
                eq("org123"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);

        // When
        ReportTemplateListResponseDto result = reportTemplateService.findAll(
                "org123", new ReportTemplateFilter(null, null, null, null, null, null), pageable);

        // Then
        assertTrue(result.getTemplates().isEmpty());
        assertEquals(0L, result.getTotal());
        assertEquals(0, result.getTotalPages());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_withSearchTerm_passesSearchToRepository() {
        // Given
        Page<ReportTemplateEntity> page = mock(Page.class);
        when(page.stream()).thenReturn(Stream.of(templateEntity));
        when(page.getTotalElements()).thenReturn(1L);
        when(page.getTotalPages()).thenReturn(1);
        Pageable pageable = PageRequest.of(0, 10);
        when(reportTemplateRepository.findAllFiltered(
                eq("org123"), eq("quarterly"), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When — search term matches both name and description fields
        ReportTemplateListResponseDto result = reportTemplateService.findAll(
                "org123", new ReportTemplateFilter("quarterly", null, null, null, null, null), pageable);

        // Then
        assertEquals(1, result.getTemplates().size());
        verify(reportTemplateRepository).findAllFiltered(
                eq("org123"), eq("quarterly"), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_withAllFilters_passesAllFiltersToRepository() {
        // Given
        LocalDate dateFrom = LocalDate.of(2024, 1, 1);
        LocalDate dateTo = LocalDate.of(2026, 12, 31);
        List<ReportTemplateType> types = List.of(ReportTemplateType.BALANCE_SHEET);
        List<DataMode> modes = List.of(DataMode.SYSTEM);

        Page<ReportTemplateEntity> page = mock(Page.class);
        when(page.stream()).thenReturn(Stream.of(templateEntity));
        when(page.getTotalElements()).thenReturn(1L);
        when(page.getTotalPages()).thenReturn(1);
        Pageable pageable = PageRequest.of(0, 10);
        when(reportTemplateRepository.findAllFiltered(
                "org123", "search", types, true, modes, dateFrom, dateTo, pageable))
                .thenReturn(page);
        when(reportTemplateMapper.toResponseDto(templateEntity)).thenReturn(templateResponseDto);

        // When
        ReportTemplateListResponseDto result = reportTemplateService.findAll(
                "org123", new ReportTemplateFilter("search", types, true, modes, dateFrom, dateTo), pageable);

        // Then
        assertEquals(1, result.getTemplates().size());
        verify(reportTemplateRepository).findAllFiltered(
                "org123", "search", types, true, modes, dateFrom, dateTo, pageable);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_pageMetadata_isCorrectlyMapped() {
        // Given
        Page<ReportTemplateEntity> page = mock(Page.class);
        when(page.stream()).thenReturn(Stream.empty());
        when(page.getTotalElements()).thenReturn(42L);
        when(page.getTotalPages()).thenReturn(5);
        Pageable pageable = PageRequest.of(2, 10);
        when(reportTemplateRepository.findAllFiltered(
                eq("org123"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);

        // When
        ReportTemplateListResponseDto result = reportTemplateService.findAll(
                "org123", new ReportTemplateFilter(null, null, null, null, null, null), pageable);

        // Then
        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(42L, result.getTotal());
        assertEquals(5, result.getTotalPages());
    }

}
