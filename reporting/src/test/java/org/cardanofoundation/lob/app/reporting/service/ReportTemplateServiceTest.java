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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleTermDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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

        Either<Problem, ReportTemplateResponseDto> reportTemplateResponseDtos = reportTemplateService.create(templateDto);

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
        when(reportTemplateTypeValidator.validateReportTemplateType(any())).thenReturn(Either.left(Problem.builder().build()));
        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Duplicate field name 'sameName' under parent 'parentName'. Field names must be unique within the same parent.", result.getLeft().getDetail());
    }

    @Test
    void create_NewTemplate_duplicateAccounts() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDtoField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto fieldDto1 = mock(ReportTemplateFieldDto.class);
        Errors errors = mock(Errors.class);

        when(dto.getFields()).thenReturn(List.of(parentDtoField));
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(parentDtoField.getChildFields()).thenReturn(List.of(fieldDto1));
        when(parentDtoField.getAccounts()).thenReturn(Set.of("acc1", "acc2"));
        when(parentDtoField.getFieldName()).thenReturn("parentName");
        when(fieldDto1.getFieldName()).thenReturn("sameName");
        when(fieldDto1.getAccounts()).thenReturn(Set.of("acc1", "acc3"));
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);

        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(dto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Duplicate account mappings found in the report template fields. Each account can only be mapped once.", result.getLeft().getDetail());
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

        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.create(templateDto);

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

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
        existing.setName("name");
        existing.setVer(1L);

        ReportEntity report = new ReportEntity();
        existing.setName("name");
        report.setId("abc");

        ReportTemplateEntity newVersion = new ReportTemplateEntity();

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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isRight());
        assertEquals(2L, newVersion.getVer());
        verify(reportTemplateRepository).save(newVersion);
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
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Not Found", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void update_TemplateNotFound_sameNameExists() {
        ReportTemplateEntity mock = mock(ReportTemplateEntity.class);
        // Given
        when(reportTemplateRepository.findLatestByOrganisationIdAndId("org123", "abc"))
                .thenReturn(Optional.of(mock));
        when(reportTemplateRepository.findLatestByOrganisationIdAndName("org123", "Test Template"))
                .thenReturn(Optional.of(mock));
        Errors errors = mock(Errors.class);
        when(errors.getAllErrors()).thenReturn(List.of());
        when(validator.validateObject(any())).thenReturn(errors);
        // When
        Either<Problem, ReportTemplateResponseDto> result = reportTemplateService.update(templateDto);

        // Then
        assertTrue(result.isLeft());
        assertEquals("Template Already Exists", result.getLeft().getTitle());
        verify(reportTemplateRepository, never()).save(any());
    }

    @Test
    void delete_Success() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.of(templateEntity));
        when(reportingRepository.findByReportTemplateId("abc")).thenReturn(new ArrayList<>());

        // When
        Either<Problem, Void> result = reportTemplateService.delete("abc");

        // Then
        assertTrue(result.isRight());
        verify(reportTemplateRepository).deleteById("abc");
    }

    @Test
    void delete_TemplateNotFound() {
        // Given
        when(reportTemplateRepository.findById("abc")).thenReturn(Optional.empty());

        // When
        Either<Problem, Void> result = reportTemplateService.delete("abc");

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
        Either<Problem, Void> result = reportTemplateService.delete("abc");

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



}
