# surrealdb-spring-boot-sample

A runnable demo application and the project's integration test suite. This
module never ships to Maven Central — it exists to show idiomatic usage and
to prove everything works against a real SurrealDB.

## Running the app

```bash
docker run --rm -p 8000:8000 surrealdb/surrealdb:v3.0.5 \
    start --bind 0.0.0.0:8000 --user root --pass root
mvn spring-boot:run -pl surrealdb-spring-boot-sample
```

On startup the Surge changelog under `src/main/resources/surge/changelog/`
creates the `person` schema and a `fn::greet_person` function.

## The integration suite

```bash
mvn -pl surrealdb-spring-boot-sample test -Pintegration-tests   # needs Docker
```

The suite was built from a coverage matrix derived from the SurrealDB
documentation — each test class owns one concern:

| Test class | Proves |
|------------|--------|
| `TransactionalIntegrationTest` | Commit, rollback, and rollback-only participation (inner `@Transactional` failure forces `UnexpectedRollbackException` with zero rows persisted) |
| `TransactionalCrudIntegrationTest` | Every template CRUD operation on the in-transaction SurrealQL translation path: update CONTENT/MERGE, deletes, read-your-writes selects, RELATE rollback, edge-table injection guard |
| `QueryResultIntegrationTest` | Eager statement-error checking (`DuplicateKeyException` straight from `query()`), record mapping, `ValueMapper`, scalars, multi-statement indexing, `queryForObject` contract |
| `DataTypeMappingIntegrationTest` | Full data-type round-trips on both execution paths: decimal precision, nanosecond datetimes, durations, uuids, arrays; NONE-removes vs NULL-keeps; numeric vs string record-id binding |
| `RelationshipIntegrationTest` | Record links as ids without `FETCH` and nested objects with it, typed RELATE edges with properties, graph traversal, SCHEMAFULL undeclared-field rejection |
| `AdvancedTypesIntegrationTest` | Bytes, geometry points, nested arrays of objects, `option<>` fields, FLEXIBLE object fields, record ranges |
| `CacheIntegrationTest` / `CacheComplexValuesIntegrationTest` | `@Cacheable` hits, cached nulls, POJO and immutable-collection round-trips, distinct key types, `@CacheEvict`, expiry purge |
| `SurgeIntegrationTest` | Startup migration apply, idempotent re-run, frozen checksums, repeatable re-run on edit, incremental versions, atomic failed migrations |
| `HealthIntegrationTest` | The actuator health indicator reports UP against a live server |

## Testcontainers setup notes

`AbstractSurrealIntegrationTest` uses the **singleton container pattern**
(manual `start()` in a static initializer, deliberately *not*
`@Container`): annotation-managed containers restart per test class, and
the changed mapped port defeats Spring's test-context cache — the
difference between a ~2-minute and a ~7-second suite. The image is pinned
to `surrealdb/surrealdb:v3.0.5` for reproducibility (override with
`-Dsurrealdb.image=...`); note the Java driver 2.1.0 only completes its
WebSocket handshake against SurrealDB 3.x servers.
