# surrealdb-spring

Spring Boot integration for [SurrealDB](https://surrealdb.com) — built on the **official Java SDK** (`com.surrealdb:surrealdb:2.1.0`).

## What this is

| Feature | Spring analogue |
|---------|-----------------|
| `SurrealTemplate` | `JdbcTemplate` |
| `@Transactional` | `DataSourceTransactionManager` |
| `@Cacheable` | `RedisCacheManager` |
| Actuator health | `SurrealHealthIndicator` |

**Not** JPA. **Not** Spring Data. **Not** a replacement for the SDK.

Primary DB and cache always use **two separate Surreal connections**.

## Stack

- Java 21
- Spring Boot 4.0.6
- SurrealDB Java SDK 2.1.0

## Modules

Each module has its own in-depth README:

| Module | What it is |
|--------|------------|
| [surrealdb-spring-core](surrealdb-spring-core/README.md) | `SurrealTemplate`, Spring transactions, eagerly-checked query results, exception translation |
| [surrealdb-spring-cache](surrealdb-spring-cache/README.md) | Spring `CacheManager` backed by SurrealDB — null contract, typed payloads, atomic writes |
| [surrealdb-spring-surge](surrealdb-spring-surge/README.md) | Surge — Flyway-style versioned + repeatable schema migrations |
| [surrealdb-spring-boot-starter](surrealdb-spring-boot-starter/README.md) | Auto-configuration, property reference, override points |
| [surrealdb-spring-boot-sample](surrealdb-spring-boot-sample/README.md) | Runnable demo + the documentation-driven integration suite |

## Quick start

**1. Add the starter**

```xml
<dependency>
    <groupId>io.github.navuk35</groupId>
    <artifactId>surrealdb-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**2. Configure** (`application.yml`)

```yaml
spring:
  surrealdb:
    url: ws://localhost:8000      # ws:// required for @Transactional
    namespace: myapp
    database: main
    username: root
    password: root
    cache:
      enabled: true
      mode: memory                # memory (per-JVM) | remote (shared server)
      namespace: cache
      database: main
      default-ttl: 10m
```

**3. Use it**

```java
@Service
public class OrderService {

    private final SurrealTemplate template;

    public OrderService(SurrealTemplate template) {
        this.template = template;
    }

    @Transactional
    public void placeOrder() {
        template.query("CREATE order SET status = 'placed'");
        template.query("CREATE order_event SET type = 'created'");
    }

    @Cacheable("orders")
    public String findOrderSummary(String id) {
        return template.query("RETURN $id", Map.of("id", id))
                .scalar(0, String.class);
    }

    public List<Order> findOrders() {
        return template.query("SELECT * FROM order ORDER BY created_at")
                .list(0, Order.class);   // maps rows onto POJOs or records
    }
}
```

`template.query(...)` returns a `SurrealQueryResult` that is **checked eagerly**:
the driver only reports statement errors when a result slot is consumed, so the
template drains every slot and throws a translated `DataAccessException`
immediately (e.g. `DuplicateKeyException` for unique-index violations) — no
silently swallowed failures. Use `.list(i, Class)` / `.first(i, Class)` for
rows, `.scalar(i, Class)` for `RETURN` values, a `ValueMapper` lambda for
shapes that fit no entity, and `.value(i)` for raw driver access. Result keys
must match field names — alias in SurrealQL (`SELECT user_name AS userName`)
when they don't.

## Configuration reference

### Primary database — `spring.surrealdb.*`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Toggle auto-configuration |
| `url` | `ws://localhost:8000` | SurrealDB endpoint (`ws://` / `wss://` for transactions) |
| `namespace` | `test` | Namespace |
| `database` | `test` | Database |
| `username` | `root` | Root username |
| `password` | `root` | Root password |

### Cache — `spring.surrealdb.cache.*`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Toggle cache auto-configuration |
| `mode` | `memory` | `memory` = per-JVM embedded store; `remote` = external server |
| `url` | — | Required when `mode=remote` |
| `namespace` | `cache` | Cache namespace |
| `database` | `main` | Cache database |
| `default-ttl` | `10m` | Entry TTL (`0` = no expiry) |

**Remote cache example:**

```yaml
spring:
  surrealdb:
    cache:
      mode: remote
      url: ws://cache-surreal:8000
      username: root
      password: secret
```

### Surge migrations — `spring.surrealdb.surge.*`

Surge is a Flyway-style migration runner for SurrealDB. Put `.surql` files in
`src/main/resources/surge/changelog/` and they are applied on startup, before
any bean touches the database:

```
surge/changelog/
├── V0_1__create_person.surql          versioned: runs once, frozen afterwards
├── V0_2__person_email_unique.surql    applied in numeric version order
└── R__person_functions.surql          repeatable: re-runs whenever it changes
```

- **Versioned** (`V<version>__<description>.surql`) migrations run exactly once,
  in numeric order (`V0_10` after `V0_2`). Applied files are checksum-frozen:
  editing one fails startup. Put evolving definitions in a repeatable instead.
- **Repeatable** (`R__<description>.surql`) migrations re-run whenever their
  content changes — ideal for `DEFINE FUNCTION OVERWRITE`, views, and anything
  you want to override in place.
- Each migration runs inside `BEGIN/COMMIT` — SurrealDB 3.x DDL is
  transactional, so a failed migration leaves nothing behind. Opt out per file
  with a leading `-- surge:no-transaction` (e.g. for huge backfills).
- Applied migrations are recorded in the `surge_changelog` table; concurrent
  instances are serialized through a `surge_lock` record.

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Run migrations on startup |
| `locations` | `classpath:surge/changelog` | Folders to scan (`classpath:` or `file:`) |
| `lock-timeout` | `1m` | How long to wait for another instance's migration lock |

## Auto-configured beans

| Bean | Description |
|------|-------------|
| `@Primary Surreal` | Primary database connection |
| `SurrealTemplate` | Data access wrapper |
| `PlatformTransactionManager` | Enables `@Transactional` |
| `@Qualifier("surrealCache") Surreal` | Separate cache connection |
| `SurrealCacheManager` | Spring `CacheManager` for `@Cacheable` |
| `SurrealHealthIndicator` | Actuator health (when actuator on classpath) |

## Limitations

- **Transactions** require `ws://` or `wss://` on the primary URL. HTTP does not support SDK transactions.
- **`queryBind()` inside `@Transactional`** routes through `tx.query(sql, params)` per SDK rules.
- **Cache `memory` mode** uses a per-JVM `surrealkv://` temp directory (SDK 2.1.0 does not support `memory://`). Not shared across pods.
- **Cache `remote` mode** is shared across instances (like external Redis) but adds network latency.
- **No JPA / Spring Data** — use `SurrealTemplate` directly.

## Build & test

```bash
# Compile and package (no Docker)
mvn verify

# Integration tests — requires Docker or OrbStack
mvn verify -Pintegration-tests
```

Integration tests use Testcontainers with a real `ws://` connection. By default `mvn verify` skips `@Tag("integration")` tests so CI/agents do not need Docker socket access.

## Status

Core, cache, starter, sample app, and integration tests are working. **Maven Central publish pending.**
