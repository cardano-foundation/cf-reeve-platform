package org.cardanofoundation.lob.app.support.security.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Minimal time-based cache. Values are recomputed once they are older than the configured ttl;
 * a failing loader is not cached.
 */
class ExpiringCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {
    }

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    ExpiringCache(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    ExpiringCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    V get(K key, Function<K, V> loader) {
        Instant now = clock.instant();
        Entry<V> entry = entries.get(key);
        if (entry != null && now.isBefore(entry.expiresAt())) {
            return entry.value();
        }
        V value = loader.apply(key);
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        return value;
    }

    void evict(K key) {
        entries.remove(key);
    }

    void clear() {
        entries.clear();
    }

}
