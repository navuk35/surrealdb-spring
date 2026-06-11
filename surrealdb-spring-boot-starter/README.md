# surrealdb-spring-boot-starter

Drop-in auto-configuration for the whole family. Add this one dependency
and configure `spring.surrealdb.*`:

```xml
<dependency>
    <groupId>io.github.navuk35</groupId>
    <artifactId>surrealdb-spring-boot-starter</artifactId>
</dependency>
```

## What gets auto-configured

| Auto-configuration | Beans | Notes |
|--------------------|-------|-------|
| `SurrealAutoConfiguration` | `surreal` (primary `Surreal` client), `surrealTemplate`, `surrealTransactionManager`, `surrealExceptionTranslator` | Connects, selects namespace/database, signs in; enables `@Transactional` |
| `SurgeAutoConfiguration` | `surgeMigrator`, `surgeMigrationInitializer` | Runs migrations at startup; a `BeanFactoryPostProcessor` makes the template and transaction manager depend on the initializer so the schema is migrated before anything queries it |
| `SurrealCacheAutoConfiguration` | `surrealCache` (a second, dedicated `Surreal` connection), `surrealCacheManager` | Cache traffic never shares the primary connection; embedded (`mode=memory`) or remote |
| `SurrealHealthContributorAutoConfiguration` | `surrealHealthIndicator` | Real server probe for actuator `/health` |

Every bean is `@ConditionalOnMissingBean` — define your own to override.
Each feature toggles independently: `spring.surrealdb.enabled`,
`spring.surrealdb.surge.enabled`, `spring.surrealdb.cache.enabled`.

## Properties

### Primary database — `spring.surrealdb.*`

| Property | Default |
|----------|---------|
| `url` | `ws://localhost:8000` |
| `namespace` / `database` | `test` / `test` |
| `username` / `password` | `root` / `root` |

### Migrations — `spring.surrealdb.surge.*`

| Property | Default |
|----------|---------|
| `enabled` | `true` |
| `locations` | `classpath:surge/changelog` |
| `lock-timeout` | `1m` |

### Cache — `spring.surrealdb.cache.*`

| Property | Default |
|----------|---------|
| `enabled` | `true` |
| `mode` | `memory` (embedded per-JVM store; `remote` for an external server) |
| `url`, `username`, `password` | required when `mode=remote` |
| `namespace` / `database` | `cache` / `main` |
| `default-ttl` | `10m` (`0` = no expiry) |

## Design decisions worth knowing

- **The cache mapper is internal.** The starter deliberately does not
  publish an `ObjectMapper` bean: an unqualified one can win over Boot's
  `JacksonAutoConfiguration` (auto-configs sort alphabetically) and replace
  the application mapper with a bare one. The cache wire format also should
  not depend on application Jackson settings.
- **Connections are eager.** The application fails fast at startup when
  SurrealDB is unreachable; a lazy-connect option is on the roadmap.
- Requires Spring Boot 4 (the health contributor uses the Boot 4 model).
