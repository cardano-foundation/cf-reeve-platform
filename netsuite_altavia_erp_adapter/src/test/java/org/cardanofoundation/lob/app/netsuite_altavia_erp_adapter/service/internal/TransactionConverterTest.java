package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FieldType;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.FinancialPeriodSource;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.Transactions;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.CodeMappingType;

@ExtendWith(MockitoExtension.class)
class TransactionConverterTest {

    @Mock
    private Validator validator;
    @Mock
    private CodesMappingService codesMappingService;
    @Mock
    private PreprocessorService preprocessorService;
    @Mock
    private TransactionTypeMapper transactionTypeMapper;
    private final FinancialPeriodSource financialPeriodSource = FinancialPeriodSource.IMPLICIT;
    private final String netsuiteInstanceId = "test-instance";

    private TransactionConverter transactionConverter;

    @BeforeEach
    void setUp() {
        transactionConverter = new TransactionConverter(validator, codesMappingService, preprocessorService, transactionTypeMapper, netsuiteInstanceId, financialPeriodSource);
    }

    @Test
    void testConvert_OrganisationIdFailed() {
        TxLine mockTxLine = mock(TxLine.class);
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.empty());
        when(mockTxLine.subsidiary()).thenReturn(10L);

        Either<FatalError, Transactions> org = transactionConverter.convert("org", "10", List.of(mockTxLine));

        assertThat(org.isLeft()).isTrue();
    }

    @Test
    void testConvert_emptyType() {
        TxLine mockTxLine = mock(TxLine.class);
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.of("orgID"));
        when(mockTxLine.subsidiary()).thenReturn(10L);
        when(mockTxLine.transactionNumber()).thenReturn("transactionID");
        when(mockTxLine.type()).thenReturn("type");
        when(transactionTypeMapper.apply("type")).thenReturn(Optional.empty());

        Either<FatalError, Transactions> org = transactionConverter.convert("orgID", "10", List.of(mockTxLine));

        assertThat(org.isLeft()).isTrue();

    }

    @Test
    @Disabled
    void testConvert_validationError() {
        TxLine mockTxLine = mock(TxLine.class);
        ConstraintViolation<TxLine> mockViolation = mock(ConstraintViolation.class);
        Path mockPath = mock(Path.class);
        when(mockPath.toString()).thenReturn("path");
        when(mockViolation.getRootBean()).thenReturn(mockTxLine);
        when(mockViolation.getMessage()).thenReturn("Message");
        when(mockViolation.getPropertyPath()).thenReturn(mockPath);
        when(mockViolation.getInvalidValue()).thenReturn("invalidaValue");
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.of("orgID"));
        when(mockTxLine.subsidiary()).thenReturn(10L);
        when(mockTxLine.transactionNumber()).thenReturn("transactionID");
        when(mockTxLine.type()).thenReturn("type");
        when(mockTxLine.date()).thenReturn(LocalDate.now());
        when(mockTxLine.exchangeRate()).thenReturn(BigDecimal.ONE);
        when(validator.validate(mockTxLine)).thenReturn(Set.of(mockViolation));
        when(transactionTypeMapper.apply("type")).thenReturn(Optional.of(TransactionType.Transfer));

        Either<FatalError, Transactions> org = transactionConverter.convert("orgID", "10", List.of(mockTxLine));

        assertThat(org.isLeft()).isTrue();
    }

    @Test
    void testConvert_SuccesNoEntries() {
        TxLine mockTxLine = mock(TxLine.class);
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.of("orgID"));
        when(mockTxLine.subsidiary()).thenReturn(10L);
        when(mockTxLine.transactionNumber()).thenReturn("transactionID");
        when(mockTxLine.type()).thenReturn("type");
        when(mockTxLine.date()).thenReturn(LocalDate.now());
        when(mockTxLine.exchangeRate()).thenReturn(BigDecimal.ONE);
        when(mockTxLine.accountMain()).thenReturn("accountMain");
        when(validator.validate(mockTxLine)).thenReturn(Set.of());
        when(transactionTypeMapper.apply("type")).thenReturn(Optional.of(TransactionType.Transfer));
        when(preprocessorService.preProcess("accountMain", FieldType.CHART_OF_ACCOUNT)).thenReturn(Either.right("Success"));

        Either<FatalError, Transactions> org = transactionConverter.convert("orgID", "10", List.of(mockTxLine));

        assertThat(org.isRight()).isTrue();
        Transactions transactions = org.get();
        Assertions.assertEquals(1, transactions.transactions().size());
        Transaction next = transactions.transactions().stream().iterator().next();
        Assertions.assertEquals("transactionID", next.getInternalTransactionNumber());
        Assertions.assertEquals("orgID", next.getOrganisation().getId());
        Assertions.assertEquals(TransactionType.Transfer, next.getTransactionType());
        Assertions.assertEquals(LocalDate.now(), next.getEntryDate());
        Assertions.assertEquals(1, next.getItems().size());
    }

    @Test
    void testConvert_ErrorCreditAndDebitSet() {
        TxLine mockTxLine = mock(TxLine.class);
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.of("orgID"));
        when(mockTxLine.subsidiary()).thenReturn(10L);
        when(mockTxLine.transactionNumber()).thenReturn("transactionID");
        when(mockTxLine.type()).thenReturn("type");
        when(mockTxLine.date()).thenReturn(LocalDate.now());
        when(mockTxLine.exchangeRate()).thenReturn(BigDecimal.ONE);
        when(mockTxLine.accountMain()).thenReturn("accountMain");
        when(mockTxLine.amountCredit()).thenReturn(BigDecimal.ONE);
        when(mockTxLine.amountDebit()).thenReturn(BigDecimal.ONE);
        when(validator.validate(mockTxLine)).thenReturn(Set.of());
        when(transactionTypeMapper.apply("type")).thenReturn(Optional.of(TransactionType.Transfer));
        when(preprocessorService.preProcess("accountMain", FieldType.CHART_OF_ACCOUNT)).thenReturn(Either.right("Success"));

        Either<FatalError, Transactions> org = transactionConverter.convert("orgID", "10", List.of(mockTxLine));

        assertThat(org.isLeft()).isTrue();
    }

    @Test
    void testConvert_SuccesCreditAndDebitEntry() {
        TxLine mockTxLine = mock(TxLine.class);
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.of("orgID"));
        when(mockTxLine.subsidiary()).thenReturn(10L);
        when(mockTxLine.transactionNumber()).thenReturn("transactionID");
        when(mockTxLine.type()).thenReturn("type");
        when(mockTxLine.date()).thenReturn(LocalDate.now());
        when(mockTxLine.exchangeRate()).thenReturn(BigDecimal.ONE);
        when(mockTxLine.accountMain()).thenReturn("accountMain");
        when(mockTxLine.amountCredit()).thenReturn(BigDecimal.ONE);
        when(validator.validate(mockTxLine)).thenReturn(Set.of());
        when(transactionTypeMapper.apply("type")).thenReturn(Optional.of(TransactionType.Transfer));
        when(preprocessorService.preProcess("accountMain", FieldType.CHART_OF_ACCOUNT)).thenReturn(Either.right("Success"));

        Either<FatalError, Transactions> org = transactionConverter.convert("orgID", "10", List.of(mockTxLine));

        assertThat(org.isRight()).isTrue();
        Transactions transactions = org.get();
        Assertions.assertEquals(1, transactions.transactions().size());
        Transaction next = transactions.transactions().stream().iterator().next();
        Assertions.assertEquals("transactionID", next.getInternalTransactionNumber());
        Assertions.assertEquals("orgID", next.getOrganisation().getId());
        Assertions.assertEquals(TransactionType.Transfer, next.getTransactionType());
        Assertions.assertEquals(LocalDate.now(), next.getEntryDate());
        Assertions.assertEquals(1, next.getItems().size());
        TransactionItem txItem = next.getItems().stream().iterator().next();
        Assertions.assertEquals(OperationType.CREDIT, txItem.getOperationType());
    }

    @Test
    void testConvert_SuccesDebitEntry() {
        TxLine mockTxLine = mock(TxLine.class);
        when(codesMappingService.getCodeMapping(netsuiteInstanceId, 10L, CodeMappingType.ORGANISATION)).thenReturn(Optional.of("orgID"));
        when(mockTxLine.subsidiary()).thenReturn(10L);
        when(mockTxLine.transactionNumber()).thenReturn("transactionID");
        when(mockTxLine.type()).thenReturn("type");
        when(mockTxLine.date()).thenReturn(LocalDate.now());
        when(mockTxLine.exchangeRate()).thenReturn(BigDecimal.ONE);
        when(mockTxLine.accountMain()).thenReturn("accountMain");
        when(mockTxLine.amountDebit()).thenReturn(BigDecimal.ONE);
        when(validator.validate(mockTxLine)).thenReturn(Set.of());
        when(transactionTypeMapper.apply("type")).thenReturn(Optional.of(TransactionType.Transfer));
        when(preprocessorService.preProcess("accountMain", FieldType.CHART_OF_ACCOUNT)).thenReturn(Either.right("Success"));

        Either<FatalError, Transactions> org = transactionConverter.convert("orgID", "10", List.of(mockTxLine));

        assertThat(org.isRight()).isTrue();
        Transactions transactions = org.get();
        Assertions.assertEquals(1, transactions.transactions().size());
        Transaction next = transactions.transactions().stream().iterator().next();
        Assertions.assertEquals("transactionID", next.getInternalTransactionNumber());
        Assertions.assertEquals("orgID", next.getOrganisation().getId());
        Assertions.assertEquals(TransactionType.Transfer, next.getTransactionType());
        Assertions.assertEquals(LocalDate.now(), next.getEntryDate());
        Assertions.assertEquals(1, next.getItems().size());
        TransactionItem txItem = next.getItems().stream().iterator().next();
        Assertions.assertEquals(OperationType.DEBIT, txItem.getOperationType());
    }
}
