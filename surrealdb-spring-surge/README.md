# Surge — schema migrations for SurrealDB

Flyway-style, version-based database migrations. Put `.surql` files in a
changelog folder; Surge applies them on startup, records what ran, and
refuses to let applied history drift.

```
src/main/resources/surge/changelog/
├── V0_1__create_person.surql           versioned: runs once, frozen afterwards
├── V0_2__person_email_unique.surql     applied in numeric version order
└── R__person_functions.surql           repeatable: re-runs whenever it changes
```

## Versioned migrations — `V<version>__<description>.surql`

- Versions are underscore-separated numbers (`V0_1`, `V1_2_3`) compared
  **numerically** — `V0_10` runs after `V0_2`.
- Each runs **exactly once**. After application its SHA-256 checksum
  (line-ending normalized, so Windows and macOS agree) is frozen: editing
  an applied file fails the next startup with a checksum mismatch.
- New files must sort **above** the latest applied version — out-of-order
  versions are rejected with a message telling you what to renumber.

## Repeatable migrations — `R__<description>.surql`

Re-run whenever their content changes, after all versioned migrations.
This is the intended "override in place" workflow, pairing naturally with
SurrealDB's idempotent DDL:

```sql
DEFINE FUNCTION OVERWRITE fn::greet($name: string) {
    RETURN "Hello, " + $name;
};
```

Edit the file, restart, and the new definition is live — recorded by
checksum in the changelog.

## Atomicity

SurrealDB 3.x DDL is **fully transactional** (verified empirically:
`DEFINE TABLE` / `FIELD` / `INDEX` / `FUNCTION` all roll back on failure),
so Surge wraps each migration in `BEGIN TRANSACTION; ... COMMIT
TRANSACTION;` — a migration that fails halfway leaves *nothing* behind,
not even implicitly created tables, and is not recorded.

The **changelog record travels inside the same transaction** as the
migration body (bookkeeping parameters are prefixed `__surge_` so they
cannot collide with the migration's own). "Applied" and "recorded" are
therefore one atomic fact: a client crash at any instant can never leave
an applied-but-unrecorded migration to be re-run on recovery. The only
field patched afterwards is `execution_time_ms` (unknowable until the
request returns; `-1` until then) — losing that patch loses a metric,
never a fact.

Opt out per file with a leading directive (for huge backfills that might
exceed backend transaction limits, or `DEFINE INDEX ... CONCURRENTLY`
whose build is asynchronous anyway):

```sql
-- surge:no-transaction
UPDATE big_table SET migrated = true;
```

Files containing their own `BEGIN` are left untouched.

## Bookkeeping tables

- `surge_changelog` — type, version, description, script, checksum,
  installed_rank, installed_on, execution_time_ms; one row per versioned
  migration, upserted per repeatable.
- `surge_lock` — a single `surge_lock:global` record serializes concurrent
  application instances; acquisition polls until `lock-timeout`. The lock
  is **leased**: the holder heartbeats every `lock-lease`/3 to extend its
  `expires_at`, so a slow migration is never mistaken for a crash — but a
  holder that died (`kill -9`, OOM) stops heartbeating, its lease expires,
  and the next instance **steals the lock atomically** (a conditional
  `UPDATE ... WHERE expires_at < time::now()`) instead of blocking
  deployments until a human intervenes.

## Use with Spring Boot

The starter auto-runs migrations before any bean touches the database
(`SurrealTemplate` and the transaction manager depend on the migration
initializer — the same wiring Boot uses for Flyway).

| Property | Default | Description |
|----------|---------|-------------|
| `spring.surrealdb.surge.enabled` | `true` | Run on startup |
| `spring.surrealdb.surge.locations` | `classpath:surge/changelog` | `classpath:` and `file:` locations |
| `spring.surrealdb.surge.lock-timeout` | `1m` | Wait for another instance's lock |
| `spring.surrealdb.surge.lock-lease` | `5m` | Lease length; expired leases are treated as crashed holders and stolen |

## Programmatic use (no Spring required)

```java
SurgeMigrator migrator = new SurgeMigrator(surreal,
        new SurgeSettings(List.of("file:/etc/myapp/migrations"), Duration.ofSeconds(30)));
SurgeResult result = migrator.migrate();
```

## SurrealDB 3.x syntax notes

- `type::record(...)` replaced `type::thing(...)`.
- Flexible fields are `DEFINE FIELD meta ON t TYPE object FLEXIBLE`
  (the 2.x `FLEXIBLE TYPE object` order is now a parse error).
