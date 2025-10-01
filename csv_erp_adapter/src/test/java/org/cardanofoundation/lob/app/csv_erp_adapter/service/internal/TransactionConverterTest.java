package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Validator;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.TransactionLine;

@ExtendWith(MockitoExtension.class)
class TransactionConverterTest {

    @Mock
    private Validator validator;

    @InjectMocks
    private TransactionConverter transactionConverter;

    @Test
    void convertToTransaction_emptyList() {
        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of());
        Assertions.assertTrue(lists.isRight());
        Assertions.assertTrue(lists.get().isEmpty());
    }

    @Test
    void convertToTransaction_typeNotFound() {
        TransactionLine line = mock(TransactionLine.class);
        when(line.getTxNumber()).thenReturn("TxNumber");
        when(line.getType()).thenReturn("UNKOWN_TYPE");
        when(line.getDate()).thenReturn("2023-10-01");

        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of(line));

        Assertions.assertTrue(lists.isRight());
        Assertions.assertEquals(1, lists.get().size());
        Assertions.assertEquals("TxNumber", lists.get().getFirst().getInternalTransactionNumber());
        Assertions.assertEquals(TransactionType.Unknown, lists.get().getFirst().getTransactionType());
    }

    @Test
    void convertToTransaction_dateParseException() {
        TransactionLine line = mock(TransactionLine.class);
        when(line.getTxNumber()).thenReturn("TxNumber");
        when(line.getType()).thenReturn("Journal");
        when(line.getDate()).thenReturn("INVALID_DATE");

        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of(line));
        Assertions.assertTrue(lists.isLeft());
        Assertions.assertEquals("Date parse exception", lists.getLeft().getTitle());
    }

    @Test
    void convertToTransaction_errorAddingAmounts() {
        TransactionLine line = mock(TransactionLine.class);
        when(line.getTxNumber()).thenReturn("TxNumber");
        when(line.getType()).thenReturn("Journal");
        when(line.getDate()).thenReturn("2023-10-01");
        when(line.getFxRate()).thenReturn("1.0");
        when(line.getAmountLCYCredit()).thenReturn("100");
        when(line.getAmountLCYDebit()).thenReturn("100");

        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of(line));
        Assertions.assertTrue(lists.isLeft());
        Assertions.assertEquals("Both debit and credit amounts are non-zero", lists.getLeft().getTitle());
    }

    @Test
    void convertToTransaction_successCredit() {
        TransactionLine line = mock(TransactionLine.class);
        when(line.getTxNumber()).thenReturn("TxNumber");
        when(line.getType()).thenReturn("Journal");
        when(line.getDate()).thenReturn("2023-10-01");
        when(line.getFxRate()).thenReturn("1.0");
        when(line.getAmountLCYCredit()).thenReturn("100");
        when(line.getAmountFCYCredit()).thenReturn("100");

        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of(line));
        Assertions.assertTrue(lists.isRight());
        Assertions.assertEquals(1, lists.get().size());
        Assertions.assertEquals("TxNumber", lists.get().get(0).getInternalTransactionNumber());
        Assertions.assertEquals(BigDecimal.valueOf(100.0), lists.get().get(0).getItems().iterator().next().getAmountLcy());
        Assertions.assertEquals(BigDecimal.valueOf(100.0), lists.get().get(0).getItems().iterator().next().getAmountFcy());
        Assertions.assertEquals(BigDecimal.valueOf(1.0), lists.get().get(0).getItems().iterator().next().getFxRate());
        Assertions.assertEquals(OperationType.CREDIT, lists.get().get(0).getItems().iterator().next().getOperationType());
    }

    @Test
    void convertToTransaction_successDebit() {
        TransactionLine line = mock(TransactionLine.class);
        when(line.getTxNumber()).thenReturn("TxNumber");
        when(line.getType()).thenReturn("Journal");
        when(line.getDate()).thenReturn("2023-10-01");
        when(line.getFxRate()).thenReturn("1.0");
        when(line.getAmountLCYDebit()).thenReturn("100");
        when(line.getAmountFCYDebit()).thenReturn("100");

        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of(line));
        Assertions.assertTrue(lists.isRight());
        Assertions.assertEquals(1, lists.get().size());
        Assertions.assertEquals("TxNumber", lists.get().get(0).getInternalTransactionNumber());
        Assertions.assertEquals(BigDecimal.valueOf(100.0), lists.get().get(0).getItems().iterator().next().getAmountLcy());
        Assertions.assertEquals(BigDecimal.valueOf(100.0), lists.get().get(0).getItems().iterator().next().getAmountFcy());
        Assertions.assertEquals(BigDecimal.valueOf(1.0), lists.get().get(0).getItems().iterator().next().getFxRate());
        Assertions.assertEquals(OperationType.DEBIT, lists.get().get(0).getItems().iterator().next().getOperationType());
    }

    @Test
    void convertToTransaction_successAllEmpty() {
        TransactionLine line = mock(TransactionLine.class);
        when(line.getTxNumber()).thenReturn("TxNumber");
        when(line.getType()).thenReturn("Journal");
        when(line.getDate()).thenReturn("2023-10-01");
        when(line.getFxRate()).thenReturn("1.0");

        Either<Problem, List<Transaction>> lists = transactionConverter.convertToTransaction("orgId", "batchId", List.of(line));
        Assertions.assertTrue(lists.isRight());
        Assertions.assertEquals(1, lists.get().size());
        Assertions.assertEquals("TxNumber", lists.get().get(0).getInternalTransactionNumber());
        Assertions.assertEquals(BigDecimal.ZERO, lists.get().get(0).getItems().iterator().next().getAmountLcy());
        Assertions.assertEquals(BigDecimal.ZERO, lists.get().get(0).getItems().iterator().next().getAmountFcy());
        Assertions.assertEquals(BigDecimal.valueOf(1.0), lists.get().get(0).getItems().iterator().next().getFxRate());
        Assertions.assertEquals(OperationType.DEBIT, lists.get().get(0).getItems().iterator().next().getOperationType());
    }

}
