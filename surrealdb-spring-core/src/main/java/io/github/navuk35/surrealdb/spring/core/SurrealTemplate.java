package io.github.navuk35.surrealdb.spring.core;

import com.surrealdb.RecordId;
import com.surrealdb.Relation;
import com.surrealdb.Response;
import com.surrealdb.Surreal;
import com.surrealdb.SurrealException;
import com.surrealdb.Transaction;
import com.surrealdb.UpType;
import com.surrealdb.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class SurrealTemplate {

    /**
     * The driver's {@link Transaction} only exposes {@code query()}, so CRUD
     * operations participating in a transaction are translated to SurrealQL.
     * Identifiers that cannot be bound as parameters (the RELATE edge table)
     * must match this pattern to rule out injection.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Surreal surreal;
    private final SurrealExceptionTranslator exceptionTranslator;

    public SurrealTemplate(Surreal surreal) {
        this(surreal, new SurrealExceptionTranslator());
    }

    public SurrealTemplate(Surreal surreal, SurrealExceptionTranslator exceptionTranslator) {
        this.surreal = surreal;
        this.exceptionTranslator = exceptionTranslator;
    }

    public Surreal getSurreal() {
        return surreal;
    }

    public SurrealQueryResult query(String surrealql) {
        return execute("query", surrealql, () -> {
            Transaction tx = currentTransaction();
            Response response = tx != null ? tx.query(surrealql) : surreal.query(surrealql);
            return new SurrealQueryResult(response, exceptionTranslator, surrealql);
        });
    }

    public SurrealQueryResult query(String surrealql, Map<String, ?> params) {
        return execute("query", surrealql, () -> {
            Transaction tx = currentTransaction();
            Response response = tx != null ? tx.query(surrealql, params)
                    : surreal.query(surrealql, params);
            return new SurrealQueryResult(response, exceptionTranslator, surrealql);
        });
    }

    public <T> T queryForObject(String surrealql, Class<T> type, int statementIndex) {
        List<T> rows = query(surrealql).list(statementIndex, type);
        if (rows.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }
        if (rows.size() > 1) {
            throw new IncorrectResultSizeDataAccessException(1, rows.size());
        }
        return rows.get(0);
    }

    public <T> List<T> create(Class<T> type, String target, T content) {
        return execute("create", target, () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                return surreal.create(type, target, content);
            }
            Response response = tx.query("CREATE type::table($tb) CONTENT $content",
                    Map.of("tb", target, "content", content));
            return takeList(response, type);
        });
    }

    public <T> Optional<T> select(Class<T> type, RecordId recordId) {
        return execute("select", recordId.toString(), () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                return surreal.select(type, recordId);
            }
            Response response = tx.query("SELECT * FROM $rid", Map.of("rid", recordId));
            List<T> rows = takeList(response, type);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        });
    }

    public <T> Iterator<T> select(Class<T> type, String target) {
        return execute("select", target, () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                return surreal.select(type, target);
            }
            Response response = tx.query("SELECT * FROM type::table($tb)", Map.of("tb", target));
            return takeList(response, type).iterator();
        });
    }

    public <T> T update(Class<T> type, RecordId recordId, UpType upType, T content) {
        return execute("update", recordId.toString(), () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                return surreal.update(type, recordId, upType, content);
            }
            String clause = switch (upType) {
                case CONTENT -> "CONTENT";
                case MERGE -> "MERGE";
                case PATCH -> "PATCH";
            };
            Response response = tx.query("UPDATE $rid " + clause + " $content",
                    Map.of("rid", recordId, "content", content));
            List<T> rows = takeList(response, type);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    public void delete(RecordId recordId) {
        executeVoid("delete", recordId.toString(), () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                surreal.delete(recordId);
            }
            else {
                tx.query("DELETE $rid", Map.of("rid", recordId));
            }
        });
    }

    public void delete(String target) {
        executeVoid("delete", target, () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                surreal.delete(target);
            }
            else {
                tx.query("DELETE type::table($tb)", Map.of("tb", target));
            }
        });
    }

    public <R extends Relation, T> R relate(Class<R> type, RecordId from, String table, RecordId to, T content) {
        return execute("relate", table, () -> {
            Transaction tx = currentTransaction();
            if (tx == null) {
                // a null CONTENT clause is rejected by the server
                return content != null
                        ? surreal.relate(type, from, table, to, content)
                        : surreal.relate(type, from, table, to);
            }
            if (!SAFE_IDENTIFIER.matcher(table).matches()) {
                throw new InvalidDataAccessApiUsageException(
                        "Edge table name is not a plain identifier: " + table);
            }
            Response response = content != null
                    ? tx.query("RELATE $from->" + table + "->$to CONTENT $content",
                            Map.of("from", from, "to", to, "content", content))
                    : tx.query("RELATE $from->" + table + "->$to",
                            Map.of("from", from, "to", to));
            List<R> rows = takeList(response, type);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    public <T> T executeInTransaction(TransactionCallback<T> callback) {
        if (TransactionSynchronizationManager.hasResource(surreal)) {
            // A transaction is already bound to this thread (outer
            // executeInTransaction or @Transactional) — participate in it and
            // leave commit/rollback to its owner.
            try {
                return callback.doInTransaction(this);
            }
            catch (RuntimeException | Error ex) {
                throw ex;
            }
            catch (Exception ex) {
                throw new UndeclaredThrowableException(ex,
                        "TransactionCallback threw checked exception");
            }
        }

        Transaction tx;
        try {
            tx = surreal.beginTransaction();
        }
        catch (RuntimeException ex) {
            throw translate("beginTransaction", null, ex);
        }
        bindTransaction(tx);
        boolean commitAttempted = false;
        try {
            T result = callback.doInTransaction(this);
            commitAttempted = true;
            tx.commit();
            return result;
        }
        catch (Throwable ex) {
            // commit() releases the native handle even on failure, so cancel()
            // is only safe when commit was never attempted.
            if (!commitAttempted) {
                try {
                    tx.cancel();
                }
                catch (RuntimeException cancelEx) {
                    ex.addSuppressed(cancelEx);
                }
            }
            if (ex instanceof SurrealException surrealException) {
                throw translate(commitAttempted ? "commit" : "executeInTransaction",
                        null, surrealException);
            }
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (ex instanceof Error error) {
                throw error;
            }
            throw new UndeclaredThrowableException(ex,
                    "TransactionCallback threw checked exception");
        }
        finally {
            unbindTransaction();
        }
    }

    private <T> List<T> takeList(Response response, Class<T> type) {
        Value value = response.take(0);
        List<T> results = new ArrayList<>();
        if (value.isArray()) {
            value.getArray().iterator(type).forEachRemaining(results::add);
        }
        else if (!value.isNone() && !value.isNull()) {
            results.add(value.get(type));
        }
        return results;
    }

    private Transaction currentTransaction() {
        SurrealResourceHolder holder = (SurrealResourceHolder) TransactionSynchronizationManager
                .getResource(surreal);
        return holder != null ? holder.getTransaction() : null;
    }

    private void bindTransaction(Transaction tx) {
        TransactionSynchronizationManager.bindResource(surreal, new SurrealResourceHolder(tx));
    }

    private void unbindTransaction() {
        TransactionSynchronizationManager.unbindResource(surreal);
    }

    private <T> T execute(String task, String resource, SurrealCallback<T> callback) {
        try {
            return callback.call();
        }
        catch (RuntimeException ex) {
            throw translate(task, resource, ex);
        }
    }

    private void executeVoid(String task, String resource, SurrealVoidCallback callback) {
        try {
            callback.call();
        }
        catch (RuntimeException ex) {
            throw translate(task, resource, ex);
        }
    }

    private DataAccessException translate(String task, String resource, Exception ex) {
        if (ex instanceof DataAccessException dataAccess) {
            return dataAccess;
        }
        return exceptionTranslator.translate(task, resource, ex);
    }

    @FunctionalInterface
    private interface SurrealCallback<T> {
        T call();
    }

    @FunctionalInterface
    private interface SurrealVoidCallback {
        void call();
    }
}
