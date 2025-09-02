package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;

class TransactionEntityRepositoryGatewayTest {

    @Mock
    private TransactionEntityRepository transactionEntityRepository;
    @Mock
    private Clock clock;

    @InjectMocks
    private TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;

    private static final Duration LOCK_TIMEOUT_DURATION = Duration.ofHours(3);
    private static final String ORG_ID = "test-org";
    private static final int BATCH_SIZE = 5;

    @BeforeEach
    void setUp() throws IllegalAccessException, NoSuchFieldException {
        MockitoAnnotations.openMocks(this);

        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(clock.instant()).thenReturn(LocalDateTime.now().toInstant(ZoneOffset.UTC));
        Field field = TransactionEntityRepositoryGateway.class.getDeclaredField("lockTimeoutDuration");
        field.setAccessible(true);
        field.set(transactionEntityRepositoryGateway, LOCK_TIMEOUT_DURATION); // Set to 3 hours
    }

    @Test
    void testFindAndLockTransactionsReadyToBeDispatchedLockNotExpired() {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();
        Set<TransactionEntity> transactions = new HashSet<>();

        TransactionEntity unlockedTx = new TransactionEntity();
        unlockedTx.setId("tx1");
        unlockedTx.setLockedAt(null);
        transactions.add(unlockedTx);

        TransactionEntity expiredLockTx = new TransactionEntity();
        expiredLockTx.setId("tx2");
        expiredLockTx.setLockedAt(LocalDateTime.now().minus(LOCK_TIMEOUT_DURATION.plusSeconds(1)));
        transactions.add(expiredLockTx);

        when(transactionEntityRepository.findFreeTransactionsByStatus(eq(ORG_ID), eq(dispatchStatuses),  any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(transactions);

        Set<TransactionEntity> result = transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched(ORG_ID, BATCH_SIZE);

        assertEquals(2, result.size());
        //Assertions.assertTrue(result.stream().allMatch(tx -> tx.getLockedAt().isPresent()));

        verify(transactionEntityRepository).findFreeTransactionsByStatus(eq(ORG_ID), eq(dispatchStatuses), any(LocalDateTime.class),  eq(Limit.of(BATCH_SIZE)));
        //verify(transactionEntityRepository).saveAll(result);
        verifyNoMoreInteractions(transactionEntityRepository);
    }

    @Test
    void testFindAndLockTransactionsReadyToBeDispatchedLockExpired() {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();
        Set<TransactionEntity> transactions = new HashSet<>();

        TransactionEntity unlockedTx = new TransactionEntity();
        unlockedTx.setId("tx1");
        unlockedTx.setLockedAt(null);
        transactions.add(unlockedTx);

        TransactionEntity expiredLockTx = new TransactionEntity();
        expiredLockTx.setId("tx2");
        expiredLockTx.setLockedAt(LocalDateTime.now().minus(LOCK_TIMEOUT_DURATION));
        transactions.add(expiredLockTx);

        when(transactionEntityRepository.findFreeTransactionsByStatus(eq(ORG_ID), eq(dispatchStatuses), any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(transactions);

        Set<TransactionEntity> result = transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched(ORG_ID, BATCH_SIZE);

        assertEquals(2, result.size());
        Assertions.assertTrue(result.stream().allMatch(tx -> tx.getLockedAt() != null));

        verify(transactionEntityRepository).findFreeTransactionsByStatus(eq(ORG_ID), eq(dispatchStatuses), any(LocalDateTime.class),eq(Limit.of(BATCH_SIZE)));
        //verify(transactionEntityRepository).saveAll(result);
        verifyNoMoreInteractions(transactionEntityRepository);
    }

    @Test
    void testFindTransactionsReadyToBeDispatchedEmptyList() {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();
        when(transactionEntityRepository.findFreeTransactionsByStatus(eq(ORG_ID), eq(dispatchStatuses), any(LocalDateTime.class), any(Limit.class)))
                .thenReturn(Set.of());

        Set<TransactionEntity> result = transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched(ORG_ID, BATCH_SIZE);

        assertEquals(0, result.size());
        verify(transactionEntityRepository).findFreeTransactionsByStatus(eq(ORG_ID), eq(dispatchStatuses), any(LocalDateTime.class), eq(Limit.of(BATCH_SIZE)));
        verifyNoMoreInteractions(transactionEntityRepository);
    }

    @Test
    void findById_shouldReturnTransactionEntity() {
        String txId = "tx123";
        TransactionEntity expectedTransaction = new TransactionEntity();
        when(transactionEntityRepository.findById(txId)).thenReturn(Optional.of(expectedTransaction));

        Optional<TransactionEntity> actualTransaction = transactionEntityRepositoryGateway.findById(txId);

        assertEquals(Optional.of(expectedTransaction), actualTransaction);
    }

    @Test
    void findDispatchedTransactionsThatAreNotFinalizedYet_shouldReturnFilteredTransactions() {
        String organisationId = "org1";
        TransactionEntity transaction = new TransactionEntity();

        transaction.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .absoluteSlot(500L)
                .build()));

        Set<TransactionEntity> transactions = Set.of(transaction);
        when(transactionEntityRepository.findDispatchedTransactionsThatAreNotFinalizedYet(anyString(), any(), any())).thenReturn(transactions);

        Set<TransactionEntity> actualTransactions = transactionEntityRepositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(organisationId, Limit.unlimited());

        assertEquals(Set.of(transaction), actualTransactions);
    }

    @Test
    void storeTransaction_shouldSaveTransactionEntity() {
        TransactionEntity transactionEntity = new TransactionEntity();

        transactionEntityRepositoryGateway.storeTransaction(transactionEntity);

        verify(transactionEntityRepository).save(transactionEntity);
    }

}
