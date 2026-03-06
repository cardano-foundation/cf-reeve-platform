package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.FilteringParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters;

@ExtendWith(MockitoExtension.class)
class TransactionConverterTest {

    @Mock
    private CoreCurrencyService coreCurrencyService;
    @Mock
    OrganisationConverter organisationConverter;

    @InjectMocks
    private TransactionConverter transactionConverter;

    private UserExtractionParameters userParams;
    private SystemExtractionParameters systemParams;
    private Transaction transaction;
    private TransactionEntity transactionEntity;

    @BeforeEach
    void setUp() {
        userParams = mock(UserExtractionParameters.class);
        systemParams = mock(SystemExtractionParameters.class);
        transaction = mock(Transaction.class);
        transactionEntity = mock(TransactionEntity.class);
    }

    @Test
    void testConvertToDbDetached_WithSystemAndUserParams() {
        when(userParams.getOrganisationId()).thenReturn("org123");
        FilteringParameters result = transactionConverter.convertToDbDetached(systemParams, userParams);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("org123", result.getOrganisationId());
    }

    @Test
    void testConvertToDbDetached_WithOptionalSystemParams() {
        when(userParams.getOrganisationId()).thenReturn("org123");
        Optional<SystemExtractionParameters> optionalParams = Optional.of(systemParams);
        FilteringParameters result = transactionConverter.convertToDbDetached(userParams, optionalParams);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("org123", result.getOrganisationId());
    }

    @Test
    void testConvertToDbDetached_UserParamsOnly() {
        when(userParams.getOrganisationId()).thenReturn("org123");
        FilteringParameters result = transactionConverter.convertToDbDetached(userParams);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("org123", result.getOrganisationId());
    }

    @Test
    void testConvertToDbDetached_SetOfTransactions() {
        when(transaction.getId()).thenReturn("tx001");
        when(transaction.getBatchId()).thenReturn("batch123");
        when(transaction.getTransactionType()).thenReturn(TransactionType.BillCredit);
        when(transaction.getAccountingPeriod()).thenReturn(YearMonth.parse("2025-03"));
        Set<Transaction> transactions = Set.of(transaction);
        Set<TransactionEntity> result = transactionConverter.convertToDbDetached(transactions, Optional.empty());
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("tx001", result.stream().findFirst().get().getId());
        Assertions.assertEquals("batch123", result.stream().findFirst().get().getBatchId());
        Assertions.assertEquals(TransactionType.BillCredit, result.stream().findFirst().get().getTransactionType());
        Assertions.assertEquals(YearMonth.parse("2025-03"), result.stream().findFirst().get().getAccountingPeriod());
    }

    @Test
    void testCopyFields() {
        TransactionEntity attached = new TransactionEntity();
        TransactionEntity detached = new TransactionEntity();
        detached.setTransactionType(TransactionType.BillCredit);

        transactionConverter.copyFields(attached, detached);
        Assertions.assertEquals(TransactionType.BillCredit, attached.getTransactionType());
    }

    @Test
    void testCopyFields_shouldCopyExtractorType() {
        TransactionEntity attached = new TransactionEntity();
        TransactionEntity detached = new TransactionEntity();
        detached.setExtractorType("CSV");

        transactionConverter.copyFields(attached, detached);
        Assertions.assertEquals("CSV", attached.getExtractorType());
    }

    @Test
    void testCopyFields_shouldCopyAllFields() {
        TransactionEntity attached = new TransactionEntity();
        TransactionEntity detached = new TransactionEntity();
        detached.setId("tx-123");
        detached.setBatchId("batch-456");
        detached.setTransactionType(TransactionType.BillCredit);
        detached.setExtractorType("NETSUITE");
        detached.setInternalTransactionNumber("INT-789");

        transactionConverter.copyFields(attached, detached);

        Assertions.assertEquals("tx-123", attached.getId());
        Assertions.assertEquals("batch-456", attached.getBatchId());
        Assertions.assertEquals(TransactionType.BillCredit, attached.getTransactionType());
        Assertions.assertEquals("NETSUITE", attached.getExtractorType());
        Assertions.assertEquals("INT-789", attached.getInternalTransactionNumber());
    }

    @Test
    void testRollbackTransaction() {
        String originalTxNumber = "TX-123";
        String rollbackSuffix = "RBK";
        String expectedTxNumber = originalTxNumber + "-" + rollbackSuffix;

        Transaction transaction = Transaction.builder()
                .internalTransactionNumber(originalTxNumber)
                .rollbackSuffix(rollbackSuffix)
                .build();

        TransactionEntity txEntity = new TransactionEntity();

        transactionConverter.rollbackTransaction(txEntity, transaction);

        Assertions.assertEquals(expectedTxNumber, txEntity.getInternalTransactionNumber());
        Assertions.assertEquals(TransactionProcessingStatus.ROLLBACK, txEntity.getProcessingStatus().orElse(null));
        Assertions.assertEquals(rollbackSuffix, txEntity.getRollbackSuffix());
    }

    @Test
    void testCopyFields_shouldCopyRollbackSuffix() {
        TransactionEntity attached = new TransactionEntity();
        TransactionEntity detached = new TransactionEntity();
        detached.setRollbackSuffix("C");

        transactionConverter.copyFields(attached, detached);

        Assertions.assertEquals("C", attached.getRollbackSuffix());
    }
}
