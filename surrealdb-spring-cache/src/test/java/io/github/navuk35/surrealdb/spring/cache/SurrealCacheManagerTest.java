package io.github.navuk35.surrealdb.spring.cache;

import com.surrealdb.Surreal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class SurrealCacheManagerTest {

    @Test
    void constructionNeverTouchesTheDatabase() {
        // an unconnected client throws on any query — so if construction
        // succeeds, schema creation is lazy and an unreachable cache store
        // can no longer prevent application startup
        assertThatCode(() -> new SurrealCacheManager(
                new Surreal(), null, Duration.ofMinutes(10), Map.of()))
                .doesNotThrowAnyException();
    }
}
