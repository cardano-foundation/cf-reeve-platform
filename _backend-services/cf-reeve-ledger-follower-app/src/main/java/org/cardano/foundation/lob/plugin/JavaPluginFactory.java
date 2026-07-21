package org.cardano.foundation.lob.plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bloxbean.cardano.yaci.store.plugin.api.EventHandlerPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.FilterPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.InitPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.PluginFactory;
import com.bloxbean.cardano.yaci.store.plugin.api.PostActionPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.PreActionPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.SchedulerPlugin;
import com.bloxbean.cardano.yaci.store.plugin.api.config.PluginDef;
import com.bloxbean.cardano.yaci.store.plugin.api.config.ScriptRef;

import lombok.extern.slf4j.Slf4j;

/**
 * Yaci-store {@link PluginFactory} for {@code lang: java} plugin definitions.
 * <p>
 * Registered automatically into the autowired {@code List<PluginFactory>} that
 * {@code PluginRegistry} consumes (same mechanism as the shipped MvelStorePluginFactory).
 * Use {@code lang: java} in {@code application.yaml} plus a {@code name:} the factory dispatches on
 * to bind a Java implementation.
 * <p>
 * Currently the only supported Java plugin is the {@code filter-address-utxos} filter
 * ({@link AddressUtxoFilterPlugin}); other plugin kinds throw {@link UnsupportedOperationException}
 * so that a misconfigured {@code lang: java} block fails loudly instead of silently doing nothing.
 * <p>
 * Addresses come from the {@code lob.utxo.filter.addresses} property (comma- or
 * whitespace-separated). Empty means "no filtering" (see {@link AddressUtxoFilterPlugin}).
 */
@Component
@Slf4j
public class JavaPluginFactory implements PluginFactory {

    /** Name handled by {@link AddressUtxoFilterPlugin}. */
    public static final String FILTER_ADDRESS_UTXOS = "filter-address-utxos";

    @Value("${lob.utxo.filter.addresses:}")
    private final Set<String> addresses = new HashSet<>();

    @Override
    public String getLang() {
        return "java";
    }

    @Override
    public void initGlobalScripts(List<ScriptRef> scriptRef) {
        // No global script support for Java plugins.
    }

    @Override
    public <T> InitPlugin createInitPlugin(PluginDef def) {
        throw new UnsupportedOperationException("Java INIT plugins are not supported: " + def);
    }

    @Override
    public <T> FilterPlugin<T> createFilterPlugin(PluginDef def) {
        if (FILTER_ADDRESS_UTXOS.equals(def.getName())) {
            @SuppressWarnings("unchecked")
            FilterPlugin<T> filter = (FilterPlugin<T>) new AddressUtxoFilterPlugin(def, addresses);
            return filter;
        }
        throw new IllegalArgumentException("Unknown Java filter plugin name: '" + def.getName()
                + "'. Supported: [" + FILTER_ADDRESS_UTXOS + "]");
    }

    @Override
    public <T> PostActionPlugin<T> createPostActionPlugin(PluginDef def) {
        throw new UnsupportedOperationException("Java POST_ACTION plugins are not supported: " + def);
    }

    @Override
    public <T> PreActionPlugin<T> createPreActionPlugin(PluginDef def) {
        throw new UnsupportedOperationException("Java PRE_ACTION plugins are not supported: " + def);
    }

    @Override
    public <T> EventHandlerPlugin<T> createEventHandlerPlugin(PluginDef def) {
        throw new UnsupportedOperationException(
                "Java EVENT_HANDLER plugins are not supported yet (use a plain Spring @EventListener instead): " + def);
    }

    @Override
    public <T> SchedulerPlugin<T> createSchedulerPlugin(PluginDef def) {
        throw new UnsupportedOperationException("Java SCHEDULER plugins are not supported: " + def);
    }

}
