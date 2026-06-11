# surrealdb-spring-cache

A Spring `CacheManager` that stores cache entries in SurrealDB — either an
embedded per-JVM store or a dedicated remote SurrealDB instance, kept
deliberately separate from your primary database connection.

## How entries are stored

One schemafull table holds all caches:

```sql
DEFINE TABLE cache_entry SCHEMAFULL;
-- fields: cache, key, payload (JSON string), is_null, expires_at, + unique index on (cache, key)
```

- Every write is a **single atomic `UPSERT`** on the record id
  `cache_entry:[$cache, $key]` — no delete-then-create race under
  concurrency.
- Reads filter expiry **server-side** (`expires_at = NONE OR expires_at >
  time::now()`), and evicts are direct record-id deletes.

## Spring Cache contract details that actually matter

- **Cached nulls are hits.** `SurrealCache` extends
  `AbstractValueAdaptingCache(allowNullValues=true)` with a dedicated
  `is_null` column, so a `@Cacheable` method that legitimately returns null
  is *not* re-executed on every call.
- **Values come back as their original types.** Payloads embed `@class`
  type information (the `GenericJackson2JsonRedisSerializer` approach) via
  `CacheObjectMapper` — a private copy of the application mapper, so your
  Jackson config is never touched. It registers available modules
  (`java.time` works out of the box), writes ISO-8601 dates, and — the
  subtle one — **always types collections and maps**, rewriting hidden JDK
  classes (`List.of()` → `ImmutableCollections$List12`,
  `Collections.unmodifiable*`, `Arrays.asList`) to portable equivalents
  (`ArrayList`, `LinkedHashSet`, `LinkedHashMap`). Without this, any cached
  `List.of(...)` value is unreadable on the way back.
- **Keys are JSON-encoded per type**, so `Integer 1` (stored `1`) and
  `String "1"` (stored `"1"`) are distinct entries.

## Expiry

`default-ttl` (per manager) stamps `expires_at` on write; expired entries
are invisible to reads. Because expiry is otherwise only enforced at read
time, write-once-read-never entries accumulate — schedule
`SurrealCacheManager.evictExpired()` if your caches hold short-lived data:

```java
@Scheduled(fixedDelay = 3_600_000)
void purgeCaches() { cacheManager.evictExpired(); }
```

## Limitations

- One TTL per manager (per-cache TTL is on the roadmap).
- Top-level *bare* final scalars (caching a lone `BigDecimal`) lose their
  exact type under `NON_FINAL` typing — wrap them in a DTO, where declared
  field types restore them.
- A cache-store outage propagates to callers by default; register a Spring
  `CacheErrorHandler` if you prefer treat-as-miss degradation.
