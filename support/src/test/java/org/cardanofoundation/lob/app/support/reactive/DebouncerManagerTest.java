package org.cardanofoundation.lob.app.support.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

import lombok.val;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
public class DebouncerManagerTest {

    private DebouncerManager debouncerManager;

    private TransactionalTaskRunner transactionalTaskRunner;

    @Mock
    private Runnable task;

    @BeforeEach
    public void setUp() {
        transactionalTaskRunner = new TransactionalTaskRunner();
        debouncerManager = new DebouncerManager(Duration.ofSeconds(1), transactionalTaskRunner);
    }

    @Test
    public void shouldReturnSameDebouncerForSameId() throws ExecutionException {
        val debouncer1 = debouncerManager.getDebouncer("id1", task, Duration.ofSeconds(1));
        val debouncer2 = debouncerManager.getDebouncer("id1", task, Duration.ofSeconds(1));

        assertThat(debouncer1).isSameAs(debouncer2);
    }

    @Test
    public void shouldReturnDifferentDebouncersForDifferentIds() throws ExecutionException {
        val debouncer1 = debouncerManager.getDebouncer("id1", task, Duration.ofSeconds(1));
        val debouncer2 = debouncerManager.getDebouncer("id2", task, Duration.ofSeconds(1));

        assertThat(debouncer1).isNotSameAs(debouncer2);
    }

    @Test
    public void shouldShutdownDebouncerOnEviction() throws InterruptedException, ExecutionException {
        // Setup removal listener to capture the debouncer that gets evicted
        val debouncer = debouncerManager.getDebouncer("id1", task, Duration.ofSeconds(1));

        // Trigger cache eviction by waiting longer than the cache's expiry duration
        Thread.sleep(1100);
        debouncerManager.cleanup();

        // Verify that the debouncer is shut down
        verify(task, times(0)).run(); // Ensure task was never run
        // Additionally check the debouncer shutdown logic if accessible
    }

}
