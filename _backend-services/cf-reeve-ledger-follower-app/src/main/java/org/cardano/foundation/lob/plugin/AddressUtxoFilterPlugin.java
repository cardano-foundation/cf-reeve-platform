package org.cardano.foundation.lob.plugin;

import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.plugin.api.FilterPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.PluginType;
import com.bloxbean.cardano.yaci.store.plugin.api.config.PluginDef;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Replaces the previous MVEL {@code utxo.unspent.save} filter with plain Java.
 * <p>
 * Only {@link AddressUtxo}s whose {@code ownerAddr} is in the configured allow-list are
 * forwarded to {@code UtxoStorageImpl.saveUnspent(...)}; the rest are dropped before the write,
 * so {@code address_utxo} grows only for monitored addresses. The items collection at the
 * {@code @Plugin(key = "utxo.unspent.save")} joinpoint is always {@code List<AddressUtxo>}.
 */
@Slf4j
public class AddressUtxoFilterPlugin implements FilterPlugin<AddressUtxo> {

    private final PluginDef pluginDef;
    private final Set<String> addresses;

    public AddressUtxoFilterPlugin(PluginDef pluginDef, Set<String> addresses) {
        this.pluginDef = pluginDef;
        this.addresses = addresses == null ? Set.of() : Set.copyOf(addresses);
        log.info("AddressUtxoFilterPlugin created: {} address(es){}",
                this.addresses.size(),
                this.addresses.isEmpty() ? " (no allow-list; all unspent UTXOs will be dropped)" : "");
    }

    @Override
    public Collection<AddressUtxo> filter(Collection<AddressUtxo> items) {
        if (addresses.isEmpty()) {
            // No allow-list configured: keep nothing.
            return List.of();
        }
        if (items == null || items.isEmpty()) {
            return items instanceof List ? items : new ArrayList<>();
        }

        List<AddressUtxo> filtered = new ArrayList<>(items.size());
        for (AddressUtxo utxo : items) {
            if (utxo.getOwnerAddr() != null && addresses.contains(utxo.getOwnerAddr())) {
                filtered.add(utxo);
            }
        }
        log.debug("filter-address-utxos: {} -> {}", items.size(), filtered.size());
        return filtered;
    }

    @Override
    public String getName() {
        return pluginDef.getName();
    }

    @Override
    public PluginDef getPluginDef() {
        return pluginDef;
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.FILTER;
    }
}
