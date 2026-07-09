package org.cardano.foundation.lob.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardano.foundation.lob.service.AddressBalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AddressBalanceMetricsTest {

    private SimpleMeterRegistry registry;
    private AddressBalanceService service;
    private AddressBalanceMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = mock(AddressBalanceService.class);
        metrics = new AddressBalanceMetrics(registry, service);
    }

    @Test
    void registersGaugePerAddressWithItsBalance() {
        when(service.getAllAddressBalances()).thenReturn(Map.of(
                "addr_test1aaa", 5_000_000L,
                "addr_test1bbb", 12_345_678L));

        metrics.refresh();

        assertThat(gauge("addr_test1aaa")).isEqualTo(5_000_000.0);
        assertThat(gauge("addr_test1bbb")).isEqualTo(12_345_678.0);
        assertThat(registry.find(AddressBalanceMetrics.METRIC_NAME).gauges()).hasSize(2);
    }

    @Test
    void updatesValueWhenBalanceChanges() {
        when(service.getAllAddressBalances()).thenReturn(Map.of("addr_test1aaa", 5_000_000L));
        metrics.refresh();

        when(service.getAllAddressBalances()).thenReturn(Map.of("addr_test1aaa", 8_000_000L));
        metrics.refresh();

        assertThat(gauge("addr_test1aaa")).isEqualTo(8_000_000.0);
        assertThat(registry.find(AddressBalanceMetrics.METRIC_NAME).gauges()).hasSize(1);
    }

    @Test
    void removesGaugeWhenAddressSpentDownToNothing() {
        when(service.getAllAddressBalances()).thenReturn(Map.of("addr_test1aaa", 5_000_000L));
        metrics.refresh();
        assertThat(registry.find(AddressBalanceMetrics.METRIC_NAME).tag("address", "addr_test1aaa").meter()).isNotNull();

        when(service.getAllAddressBalances()).thenReturn(Map.of());
        metrics.refresh();

        assertThat(registry.find(AddressBalanceMetrics.METRIC_NAME).tag("address", "addr_test1aaa").meter()).isNull();
        assertThat(registry.find(AddressBalanceMetrics.METRIC_NAME).gauges()).isEmpty();
    }

    @Test
    void keepsLastKnownValueWhenRefreshThrows() {
        when(service.getAllAddressBalances()).thenReturn(Map.of("addr_test1aaa", 5_000_000L));
        metrics.refresh();

        when(service.getAllAddressBalances()).thenThrow(new RuntimeException("table missing"));

        // must not throw, and must keep the existing series untouched
        metrics.refresh();

        assertThat(gauge("addr_test1aaa")).isEqualTo(5_000_000.0);
    }

    @Test
    void addsSeriesForNewlyAppearingAddress() {
        when(service.getAllAddressBalances()).thenReturn(Map.of("addr_test1aaa", 5_000_000L));
        metrics.refresh();

        when(service.getAllAddressBalances()).thenReturn(Map.of(
                "addr_test1aaa", 5_000_000L,
                "addr_test1ccc", 999L));
        metrics.refresh();

        assertThat(gauge("addr_test1aaa")).isEqualTo(5_000_000.0);
        assertThat(gauge("addr_test1ccc")).isEqualTo(999.0);
        assertThat(registry.find(AddressBalanceMetrics.METRIC_NAME).gauges()).hasSize(2);
    }

    private Double gauge(String address) {
        var g = registry.find(AddressBalanceMetrics.METRIC_NAME).tag("address", address).gauge();
        assertThat(g).as("gauge for %s", address).isNotNull();
        return g.value();
    }
}
