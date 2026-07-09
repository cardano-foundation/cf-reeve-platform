package org.cardano.foundation.lob.plugin;

import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.plugin.api.PluginType;
import com.bloxbean.cardano.yaci.store.plugin.api.config.PluginDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AddressUtxoFilterPluginTest {

    private static final String ADDR_A = "addr_test1qaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ADDR_B = "addr_test1qzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
    private static final String ADDR_OTHER = "addr_test1qbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private AddressUtxoFilterPlugin plugin(Set<String> addresses) {
        return new AddressUtxoFilterPlugin(
                new PluginDef("filter-address-utxos", "java", null, null, null, null),
                addresses);
    }

    private AddressUtxo utxo(String ownerAddr) {
        return AddressUtxo.builder().ownerAddr(ownerAddr).txHash("tx-" + ownerAddr).outputIndex(0).build();
    }

    @Test
    void keepsOnlyAllowListedAddresses() {
        var filter = plugin(Set.of(ADDR_A, ADDR_B));
        var input = List.of(utxo(ADDR_A), utxo(ADDR_OTHER), utxo(ADDR_B), utxo(ADDR_OTHER));

        var result = filter.filter(input);

        assertThat(result).extracting(AddressUtxo::getOwnerAddr)
                .containsExactlyInAnyOrder(ADDR_A, ADDR_B);
    }

    @Test
    void emptyAllowListKeepsNothing() {
        var filter = plugin(Set.of());
        var input = List.of(utxo(ADDR_A), utxo(ADDR_OTHER));

        var result = filter.filter(input);

        assertThat(result).isEmpty(); // no allow-list configured: keep nothing
    }

    @Test
    void dropsUtxosWithNullOwnerAddr() {
        var filter = plugin(Set.of(ADDR_A));
        var input = List.of(utxo(ADDR_A), utxo(null));

        var result = filter.filter(input);

        assertThat(result).extracting(AddressUtxo::getOwnerAddr).containsExactly(ADDR_A);
    }

    @Test
    void emptyInputProducesEmptyOutputWithAllowListSet() {
        var filter = plugin(Set.of(ADDR_A));
        assertThat(filter.filter(List.of())).isEmpty();
    }

    @Test
    void nullMetadataFieldsOnPluginDefAreAccepted() {
        var filter = plugin(Set.of(ADDR_A));
        assertThat(filter.getName()).isEqualTo("filter-address-utxos");
        assertThat(filter.getPluginType()).isEqualTo(PluginType.FILTER);
        assertThat(filter.getPluginDef().getLang()).isEqualTo("java");
    }
}
