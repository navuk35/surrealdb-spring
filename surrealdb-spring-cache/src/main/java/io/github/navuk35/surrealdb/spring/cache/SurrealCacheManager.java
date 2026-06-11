package io.github.navuk35.surrealdb.spring.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surrealdb.Surreal;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SurrealCacheManager implements CacheManager {

    private final Surreal surreal;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public SurrealCacheManager(Surreal surreal, ObjectMapper objectMapper, Duration defaultTtl) {
        this.surreal = surreal;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.defaultTtl = defaultTtl != null ? defaultTtl : Duration.ofMinutes(10);
        ensureSchema();
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name,
                cacheName -> new SurrealCache(cacheName, surreal, objectMapper, defaultTtl));
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(caches.keySet());
    }

    /**
     * Deletes expired entries across all caches. Expiry is otherwise only
     * enforced at read time, so write-once-read-never entries accumulate —
     * call this from a scheduled task (e.g. {@code @Scheduled}) if caches
     * hold short-lived data.
     */
    public void evictExpired() {
        surreal.query("DELETE cache_entry WHERE expires_at != NONE AND expires_at < time::now()");
    }

    private void ensureSchema() {
        surreal.query("""
                DEFINE TABLE IF NOT EXISTS cache_entry SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS cache ON cache_entry TYPE string;
                DEFINE FIELD IF NOT EXISTS key ON cache_entry TYPE string;
                DEFINE FIELD IF NOT EXISTS payload ON cache_entry TYPE string;
                DEFINE FIELD IF NOT EXISTS is_null ON cache_entry TYPE option<bool>;
                DEFINE FIELD IF NOT EXISTS expires_at ON cache_entry TYPE option<datetime>;
                DEFINE INDEX IF NOT EXISTS cache_key ON cache_entry FIELDS cache, key UNIQUE;
                """);
    }
}
