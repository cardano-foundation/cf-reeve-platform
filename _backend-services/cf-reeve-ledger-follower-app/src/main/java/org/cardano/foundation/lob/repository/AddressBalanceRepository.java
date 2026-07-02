package org.cardano.foundation.lob.repository;

import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * JPA repository over yaci-store's {@code address_utxo} table (mapped by
 * {@link AddressUtxoEntity}). Because the {@code AddressUtxoFilterPlugin} only lets
 * monitored-address UTXOs through to {@code saveUnspent(...)}, and yaci-store removes rows
 * on spend, this table holds only live (unspent) UTXOs for monitored addresses — so
 * {@code SUM(lovelace_amount) GROUP BY owner_addr} is the current balance per address.
 */
public interface AddressBalanceRepository extends JpaRepository<AddressUtxoEntity, UtxoId> {

    @Query("select e.ownerAddr as ownerAddr, coalesce(sum(e.lovelaceAmount), 0) as total "
            + "from AddressUtxoEntity e "
            + "where e.ownerAddr is not null "
            + "group by e.ownerAddr")
    List<AddressBalance> findAddressBalances();
}
