package org.cardano.foundation.lob.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only access to the {@code address_utxo} table populated by yaci-store's utxo indexer.
 * <p>
 * Because the {@code AddressUtxoFilterPlugin} only lets monitored-address UTXOs through to
 * {@code saveUnspent(...)}, this table contains only unspent UTXOs for monitored addresses.
 * Spent UTXOs are removed from {@code address_utxo} by yaci-store on spend, so
 * {@code SUM(lovelace_amount) GROUP BY owner_addr} yields the current live balance per address.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class AddressBalanceRepository {

    private static final String BALANCES_SQL =
            "SELECT owner_addr, COALESCE(SUM(lovelace_amount), 0) AS total " +
            "FROM address_utxo " +
            "WHERE owner_addr IS NOT NULL " +
            "GROUP BY owner_addr";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return a map of {@code ownerAddr -> lovelace balance} for every address present in
     *         {@code address_utxo}. Empty map if no UTXOs are stored (e.g. no addresses
     *         configured or indexer not yet synced).
     */
    public Map<String, Long> findAllAddressBalances() {
        return jdbcTemplate.query(BALANCES_SQL, rs -> {
            Map<String, Long> balances = new LinkedHashMap<>();
            while (rs.next()) {
                balances.put(rs.getString("owner_addr"), rs.getLong("total"));
            }
            return balances;
        });
    }
}
