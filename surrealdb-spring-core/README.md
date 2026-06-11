# surrealdb-spring-core

The data-access heart of the project: `SurrealTemplate`, Spring-managed
transactions, eagerly-checked query results, and exception translation.

## SurrealTemplate

The template wraps the official SurrealDB Java driver the way `JdbcTemplate`
wraps JDBC: queries and CRUD methods that translate driver errors into
Spring's `DataAccessException` hierarchy and transparently participate in
Spring transactions.

```java
SurrealQueryResult result = template.query(
        "SELECT * FROM person WHERE age > $min", Map.of("min", 21));
List<Person> people = result.list(0, Person.class);

List<Person> created = template.create(Person.class, "person", new Person(...));
Optional<Person> one  = template.select(Person.class, recordId);
Person updated        = template.update(Person.class, recordId, UpType.MERGE, patch);
template.delete(recordId);
Wrote edge            = template.relate(Wrote.class, author, "wrote", post, content);
```

## SurrealQueryResult — why it exists

The driver reports **statement-level errors lazily**: `Surreal.query()`
returns a `Response` whose error slots only throw when consumed with
`take(i)`. A fire-and-forget `CREATE` that violates a unique index would
fail silently. Think of `Response` as a stack of sealed envelopes — one per
statement — where one may contain a rejection letter you never open.

`SurrealQueryResult` drains every slot at construction, so errors throw
**immediately** from `template.query(...)`, translated and typed:

| Accessor | Use for |
|----------|---------|
| `list(i, Class)` | Rows onto POJOs/records — the driver converter handles nested objects, records, generic collections, `RecordId`, `ZonedDateTime`, `Duration`, `UUID`, `Geometry` |
| `list(i, ValueMapper)` | Shapes that fit no entity (aggregations, projections) |
| `first(i, Class)` | Single optional row |
| `scalar(i, Class)` | `RETURN` values — scalars dispatch to typed `Value` accessors because the driver converter only handles object shapes |
| `value(i)` | Raw driver `Value` for full manual control |

Caveats worth knowing:

- **Result keys must match field names.** The driver converter has no
  annotation support — alias in SurrealQL instead:
  `SELECT user_name AS userName FROM ...`.
- **Multi-statement queries are not atomic** without `BEGIN/COMMIT`: when
  statement 2 fails, statement 1 has already committed. The thrown
  exception names the failing statement; wrap in a transaction if you need
  all-or-nothing.

## Transactions

`SurrealTransactionManager` extends `AbstractPlatformTransactionManager`
using the same resource-binding pattern as `DataSourceTransactionManager`:
the driver `Transaction` is bound to the thread via
`TransactionSynchronizationManager`, keyed by the `Surreal` instance.

Design notes:

- The driver's `Transaction` exposes **only `query()`** — no CRUD methods.
  Template CRUD calls inside a transaction are therefore translated into
  parameterized SurrealQL (`CREATE type::table($tb) CONTENT $content`,
  `UPDATE $rid MERGE $content`, `DELETE $rid`,
  `RELATE $from->edge->$to`). The RELATE edge-table name cannot be a bind
  parameter, so it is validated as a plain identifier to rule out injection.
- **Rollback-only participation works**: the transaction object implements
  `SmartTransactionObject`, so an inner `@Transactional` method that fails
  marks the shared transaction, and the outer commit raises
  `UnexpectedRollbackException` instead of silently committing.
- Custom isolation levels are rejected (`InvalidIsolationLevelException`);
  suspension (`REQUIRES_NEW`) is not supported.
- SurrealDB rolls back **everything**, including DDL and implicitly created
  tables — a rolled-back `CREATE` on a brand-new table removes the table
  itself.

`executeInTransaction(callback)` offers programmatic transactions: it
participates if one is already bound, propagates your business exceptions
unchanged, and never touches the native transaction handle after a failed
commit (the driver releases it even on failure).

## Exception translation

`SurrealExceptionTranslator` maps driver exceptions onto Spring's hierarchy
and is replaceable as a bean for custom mappings:

| Driver | Spring |
|--------|--------|
| `NotFoundException` | `EmptyResultDataAccessException` |
| `AlreadyExistsException` | `DataIntegrityViolationException` |
| unique-index violation (a `ServerException` recognizable only by message) | `DuplicateKeyException` |
| `QueryException` | `InvalidDataAccessResourceUsageException` |
| other `SurrealException` | `DataAccessResourceFailureException` |
