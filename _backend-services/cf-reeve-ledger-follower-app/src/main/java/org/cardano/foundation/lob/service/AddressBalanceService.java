package org.cardano.foundation.lob.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardano.foundation.lob.repository.AddressBalance;
import org.cardano.foundation.lob.repository.AddressBalanceRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes current UTXO balances (in lovelace) for the monitored addresses that have been
 * persisted to {@code address_utxo} by the yaci-store utxo indexer (filtered by
 * {@link org.cardano.foundation.lob.plugin.AddressUtxoFilterPlugin}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AddressBalanceService {

    private final AddressBalanceRepository addressBalanceRepository;

    /**
     * @return a map of {@code ownerAddr -> lovelace balance} for every monitored address
     *         that currently has unspent UTXOs. Addresses with no UTXOs are absent from the map.
     */
    public Map<String, Long> getAllAddressBalances() {
        List<AddressBalance> rows = addressBalanceRepository.findAddressBalances();
        Map<String, Long> balances = new LinkedHashMap<>(rows.size());
        for (AddressBalance row : rows) {
            balances.put(row.getOwnerAddr(),
                    row.getTotal() != null ? row.getTotal().longValue() : 0L);
        }
        log.debug("Computed UTXO balances for {} address(es)", balances.size());
        return balances;
    }
}
