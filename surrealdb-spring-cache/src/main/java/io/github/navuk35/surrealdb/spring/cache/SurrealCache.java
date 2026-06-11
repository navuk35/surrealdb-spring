package io.github.navuk35.surrealdb.spring.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surrealdb.Response;
import com.surrealdb.Surreal;
import com.surrealdb.Value;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

public class SurrealCache extends AbstractValueAdaptingCache {

    private final String name;
    private final Surreal surreal;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;

    public SurrealCache(String name, Surreal surreal, ObjectMapper objectMapper, Duration defaultTtl) {
        super(true);
        this.name = name;
        this.surreal = surreal;
        this.objectMapper = CacheObjectMapper.create(objectMapper);
        this.defaultTtl = defaultTtl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return surreal;
    }

    @Override
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            @SuppressWarnings("unchecked")
            T value = (T) wrapper.get();
            return value;
        }
        T value;
        try {
            value = valueLoader.call();
        }
        catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
        put(key, value);
        return value;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        Object storeValue = toStoreValue(value);
        boolean isNull = storeValue instanceof NullValue;
        String payload = isNull ? "" : serializeValue(value);
        String serializedKey = serializeKey(key);

        if (defaultTtl.isZero() || defaultTtl.isNegative()) {
            surreal.query("""
                    UPSERT type::record('cache_entry', [$cache, $key])
                    SET cache = $cache, key = $key, payload = $payload,
                        is_null = $isNull, expires_at = NONE
                    """, Map.of(
                    "cache", name,
                    "key", serializedKey,
                    "payload", payload,
                    "isNull", isNull));
        }
        else {
            surreal.query("""
                    UPSERT type::record('cache_entry', [$cache, $key])
                    SET cache = $cache, key = $key, payload = $payload,
                        is_null = $isNull, expires_at = type::datetime($expiresAt)
                    """, Map.of(
                    "cache", name,
                    "key", serializedKey,
                    "payload", payload,
                    "isNull", isNull,
                    "expiresAt", Instant.now().plus(defaultTtl).toString()));
        }
    }

    @Override
    public void evict(Object key) {
        surreal.query("DELETE type::record('cache_entry', [$cache, $key])",
                Map.of("cache", name, "key", serializeKey(key)));
    }

    @Override
    public void clear() {
        surreal.query("DELETE cache_entry WHERE cache = $cache", Map.of("cache", name));
    }

    @Override
    @Nullable
    protected Object lookup(Object key) {
        Response response = surreal.query("""
                SELECT * FROM type::record('cache_entry', [$cache, $key])
                WHERE expires_at = NONE OR expires_at > time::now()
                """, Map.of("cache", name, "key", serializeKey(key)));

        Value result = response.take(0);
        if (!result.isArray() || result.getArray().len() == 0) {
            return null;
        }
        Value row = result.getArray().get(0);
        if (!row.isObject()) {
            return null;
        }
        com.surrealdb.Object entry = row.getObject();

        Value isNullValue = entry.get("is_null");
        if (isNullValue != null && isNullValue.isBoolean() && isNullValue.getBoolean()) {
            return NullValue.INSTANCE;
        }

        Value payloadValue = entry.get("payload");
        if (payloadValue == null || payloadValue.isNone() || payloadValue.isNull()) {
            return null;
        }
        return deserializeValue(payloadValue.getString());
    }

    private String serializeKey(Object key) {
        if (key == null) {
            return "null";
        }
        try {
            // JSON-encode every key type, Strings included: the quoted form
            // "1" stays distinct from the numeric form 1
            return objectMapper.writeValueAsString(key);
        }
        catch (JsonProcessingException ex) {
            // e.g. SimpleKey exposes no Jackson-visible properties
            return key.getClass().getName() + ":" + key;
        }
    }

    private String serializeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cache value is not serializable", ex);
        }
    }

    private Object deserializeValue(String payload) {
        try {
            return objectMapper.readValue(payload, Object.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Corrupt cache payload in cache '" + name + "'", ex);
        }
    }
}
