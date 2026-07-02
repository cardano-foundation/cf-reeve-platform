package org.cardano.foundation.lob.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardano.foundation.lob.service.AddressBalanceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposes the current UTXO balance (in lovelace) of every monitored address as a Prometheus
 * gauge, scraped from {@code /actuator/prometheus}:
 *
 * <pre>
 *   cardano_address_balance{address="addr_test1..."} 5000000
 * </pre>
 *
 * <p>The set of address time series is dynamic: yaci-store's utxo indexer (filtered by
 * {@link org.cardano.foundation.lob.plugin.AddressUtxoFilterPlugin}) only keeps monitored-address
 * UTXOs in {@code address_utxo}, and removes rows on spend. So {@link #refresh()} re-queries the
 * balances on a schedule and reconciles the registered gauges — adding series for addresses that
 * newly hold UTXOs and removing series for addresses that have spent down to nothing, so no stale
 * values are reported.
 *
 * <p>Values are read live from {@link #currentBalances} at scrape time (Gauge supplier), so the
 * metric reflects the most recent successful refresh even between scrapes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AddressBalanceMetrics {

    /** Micrometer name (Prometheus exporter emits it as {@code cardano_address_balance}). */
    public static final String METRIC_NAME = "cardano.address.balance";
    public static final String ADDRESS_TAG = "address";

    private final MeterRegistry meterRegistry;
    private final AddressBalanceService addressBalanceService;

    /** address -> last known lovelace balance; read by gauge suppliers at scrape time. */
    private final Map<String, Long> currentBalances = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedRateString = "${lob.metrics.address-balance.refresh-interval:PT30S}")
    public void refresh() {
        Map<String, Long> fresh;
        try {
            fresh = addressBalanceService.getAllAddressBalances();
        } catch (Exception e) {
            // e.g. address_utxo not migrated yet, or DB transient issue. Keep the last known
            // gauges rather than wiping series on a transient failure; retry on the next tick.
            log.warn("Failed to refresh address balances metric: {}", e.getMessage());
            return;
        }

        // Remove gauges for addresses that no longer have any unspent UTXOs.
        for (Meter meter : meterRegistry.find(METRIC_NAME).meters()) {
            String addr = meter.getId().getTag(ADDRESS_TAG);
            if (addr != null && !fresh.containsKey(addr)) {
                meterRegistry.remove(meter);
                currentBalances.remove(addr);
            }
        }

        // Register gauges for new addresses; update the live map for existing ones.
        fresh.forEach((addr, balance) -> {
            currentBalances.put(addr, balance);
            if (meterRegistry.find(METRIC_NAME).tag(ADDRESS_TAG, addr).meter() == null) {
                Gauge.builder(METRIC_NAME, () -> currentBalances.getOrDefault(addr, 0L))
                        .tag(ADDRESS_TAG, addr)
                        .description("Current UTXO balance in lovelace for a monitored Cardano address")
                        .register(meterRegistry);
            }
        });

        log.debug("Refreshed address balance metrics: {} address(es)", fresh.size());
    }
}
