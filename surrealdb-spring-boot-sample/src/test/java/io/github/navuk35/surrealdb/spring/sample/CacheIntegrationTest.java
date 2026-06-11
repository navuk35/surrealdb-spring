package io.github.navuk35.surrealdb.spring.sample;

import com.surrealdb.Surreal;
import io.github.navuk35.surrealdb.spring.cache.SurrealCacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CacheIntegrationTest extends AbstractSurrealIntegrationTest {

    @Autowired
    private CachedGreetingService greetingService;

    @Autowired
    private SurrealCacheManager cacheManager;

    @Test
    void cacheableSkipsSecondLookup() {
        assertThat(greetingService.greet("world")).isEqualTo("Hello, world");
        assertThat(greetingService.greet("world")).isEqualTo("Hello, world");
        assertThat(greetingService.callCount()).isEqualTo(1);
    }

    @Test
    void cacheableNullResultIsCachedAndNotRecomputed() {
        assertThat(greetingService.findNickname("world")).isNull();
        assertThat(greetingService.findNickname("world")).isNull();

        assertThat(greetingService.nicknameCallCount())
                .as("a cached null must count as a hit, not be recomputed on every call")
                .isEqualTo(1);
    }

    @Test
    void cacheablePojoComesBackAsItsOriginalType() {
        Greeting first = greetingService.greetProfile("world");
        Greeting second = greetingService.greetProfile("world");

        assertThat(second)
                .as("a cache hit must return the POJO type, not a raw map")
                .isInstanceOf(Greeting.class);
        assertThat(second.getName()).isEqualTo(first.getName());
        assertThat(second.getMessage()).isEqualTo("Hello, world");
        assertThat(greetingService.profileCallCount()).isEqualTo(1);
    }

    @Test
    void integerAndStringKeysWithSameTextAreDistinctEntries() {
        Cache cache = cacheManager.getCache("key-types");
        cache.put(1, "int-value");
        cache.put("1", "string-value");

        assertThat(cache.get(1).get())
                .as("Integer 1 and String \"1\" must not collide")
                .isEqualTo("int-value");
        assertThat(cache.get("1").get()).isEqualTo("string-value");
    }

    @Test
    void perCacheTtlOverridesTheDefault() {
        SurrealCacheManager manager = new SurrealCacheManager(
                (Surreal) cacheManager.getCache("ttl-probe").getNativeCache(),
                null,
                java.time.Duration.ofMinutes(10),
                java.util.Map.of("eternal", java.time.Duration.ZERO,
                        "short-lived", java.time.Duration.ofSeconds(5)));

        manager.getCache("eternal").put("k", "v");
        manager.getCache("short-lived").put("k", "v");
        manager.getCache("defaulted").put("k", "v");

        Surreal db = (Surreal) manager.getCache("eternal").getNativeCache();
        java.util.List<String> expiries = new java.util.ArrayList<>();
        db.query("SELECT cache, expires_at FROM cache_entry WHERE cache IN ['eternal','short-lived','defaulted'] ORDER BY cache")
                .take(0).getArray().forEach(row -> {
                    com.surrealdb.Object entry = row.getObject();
                    com.surrealdb.Value expiresAt = entry.get("expires_at");
                    expiries.add(entry.get("cache").getString() + ":"
                            + (expiresAt == null || expiresAt.isNone() ? "none" : "set"));
                });

        assertThat(expiries).containsExactly(
                "defaulted:set",      // falls back to the manager default
                "eternal:none",       // ZERO = never expires
                "short-lived:set");
    }

    @Test
    void evictExpiredPurgesOnlyExpiredEntries() {
        Cache cache = cacheManager.getCache("purge-test");
        cache.put("alive", "fresh");
        Surreal cacheDb = (Surreal) cache.getNativeCache();
        cacheDb.query("""
                UPSERT type::record('cache_entry', 'stale')
                SET cache = 'purge-test', key = 'stale-key', payload = '"old"',
                    is_null = false, expires_at = time::now() - 1h
                """);

        cacheManager.evictExpired();

        assertThat(cache.get("alive")).as("unexpired entries survive the purge").isNotNull();
        long staleRows = cacheDb
                .query("RETURN count(SELECT * FROM cache_entry WHERE key = 'stale-key')")
                .take(0).getLong();
        assertThat(staleRows).as("expired entries are purged").isZero();
    }

    @Test
    void cacheEvictForcesRecomputation() {
        assertThat(greetingService.greet("evictee")).isEqualTo("Hello, evictee");
        int callsAfterFirst = greetingService.callCount();

        greetingService.forget("evictee");

        assertThat(greetingService.greet("evictee")).isEqualTo("Hello, evictee");
        assertThat(greetingService.callCount()).isEqualTo(callsAfterFirst + 1);
    }
}
