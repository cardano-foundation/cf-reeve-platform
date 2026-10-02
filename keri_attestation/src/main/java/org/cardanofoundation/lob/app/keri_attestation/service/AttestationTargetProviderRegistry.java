package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Collects every {@link AttestationTargetProvider} bean in the application context, keyed by
 * {@link AttestationTargetProvider#targetType()}. Has no dependency on
 * {@code SignifyClient} or any other KERI-agent wiring, so — unlike most services in this module —
 * it is <em>not</em> gated on {@code lob.keri-attestation.keria.url}: it must construct cleanly even
 * when no host module contributes a provider (module enabled with no consumers yet, or a Spring
 * context test that only component-scans this package), simply exposing an always-empty registry in
 * that case.
 */
@Service
public class AttestationTargetProviderRegistry {

    private final Map<String, AttestationTargetProvider> providersByType;

    public AttestationTargetProviderRegistry(List<AttestationTargetProvider> providers) {
        Map<String, AttestationTargetProvider> byType = new LinkedHashMap<>();
        for (AttestationTargetProvider provider : providers) {
            byType.put(provider.targetType(), provider);
        }
        this.providersByType = Map.copyOf(byType);
    }

    public Optional<AttestationTargetProvider> forType(String targetType) {
        return Optional.ofNullable(providersByType.get(targetType));
    }
}
