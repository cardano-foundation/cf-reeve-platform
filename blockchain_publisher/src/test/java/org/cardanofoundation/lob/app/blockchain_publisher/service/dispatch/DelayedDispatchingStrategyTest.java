package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;

@ExtendWith(MockitoExtension.class)
class DelayedDispatchingStrategyTest {

    private DelayedDispatchingStrategy<TransactionEntity> strategy;

    @BeforeEach
    void setUp() {
        strategy = new DelayedDispatchingStrategy<>(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(strategy, "minTxCount", 2);
        ReflectionTestUtils.setField(strategy, "maxTxDelay", Duration.ofHours(1));
    }

    @Test
    void apply_success() {
        TransactionEntity mock1 = mock(TransactionEntity.class);
        TransactionEntity mock2 = mock(TransactionEntity.class);
        TransactionEntity mock3 = mock(TransactionEntity.class);

        // all within the delay, but we have more than minTxCount, so all should be included
        when(mock1.getCreatedAt()).thenReturn(LocalDateTime.of(2026,1,1, 0,1));
        when(mock2.getCreatedAt()).thenReturn(LocalDateTime.of(2026,1,1, 0,2));
        when(mock3.getCreatedAt()).thenReturn(LocalDateTime.of(2026,1,1, 0,3));

        Set<TransactionEntity> returnTxs = strategy.apply("org1", Set.of(mock1, mock2, mock3));

        assertTrue(returnTxs.contains(mock1));
        assertTrue(returnTxs.contains(mock2));
        assertTrue(returnTxs.contains(mock3));
    }

    @Test
    void apply_prioritisesExpiredTransactions() {
        TransactionEntity mock1 = mock(TransactionEntity.class);
        TransactionEntity mock2 = mock(TransactionEntity.class);
        TransactionEntity mock3 = mock(TransactionEntity.class);

        // mock1 is expired, but we have more than minTxCount, so all should be included but mock1 should be prioritised
        when(mock1.getCreatedAt()).thenReturn(LocalDateTime.of(2026,1,1, 0,2));
        when(mock2.getCreatedAt()).thenReturn(LocalDateTime.of(2026,1,1, 0,3));
        when(mock3.getCreatedAt()).thenReturn(LocalDateTime.of(2025,12,31, 22,0));

        Set<TransactionEntity> returnTxs = strategy.apply("org1", Set.of(mock1, mock2, mock3));
        // Prioritized transaction is first in the returned set
        assertTrue(returnTxs.iterator().next() == mock3);
        assertTrue(returnTxs.contains(mock1));
        assertTrue(returnTxs.contains(mock2));
    }

    @Test
    void apply_notEnoughTransactions() {
        TransactionEntity mock1 = mock(TransactionEntity.class);

        // mock1 is expired, but we have more than minTxCount, so all should be included but mock1 should be prioritised
        when(mock1.getCreatedAt()).thenReturn(LocalDateTime.of(2026,1,1, 0,2));

        Set<TransactionEntity> returnTxs = strategy.apply("org1", Set.of(mock1));
        // Prioritized transaction is first in the returned set
        assertTrue(returnTxs.isEmpty());
    }


}
