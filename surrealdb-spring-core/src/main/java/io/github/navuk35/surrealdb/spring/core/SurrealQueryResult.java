package io.github.navuk35.surrealdb.spring.core;

import com.surrealdb.RecordId;
import com.surrealdb.Response;
import com.surrealdb.Value;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Eagerly-checked result of a SurrealQL query. The driver reports
 * statement-level errors lazily — only when a result slot is consumed — so
 * this wrapper drains every slot on construction and translates the first
 * failure into a {@link org.springframework.dao.DataAccessException},
 * restoring the fail-fast contract Spring users expect from a template.
 *
 * <p>Note that a multi-statement query without {@code BEGIN/COMMIT} is not
 * atomic: statements before the failing one have already been applied when
 * the exception is thrown.
 */
public class SurrealQueryResult {

    private final List<Value> values;

    SurrealQueryResult(Response response, SurrealExceptionTranslator translator, String sql) {
        int size = response.size();
        List<Value> taken = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            try {
                taken.add(response.take(i));
            }
            catch (RuntimeException ex) {
                if (ex instanceof DataAccessException dataAccess) {
                    throw dataAccess;
                }
                throw translator.translate("statement " + i, sql, ex);
            }
        }
        this.values = taken;
    }

    /** Number of statement results in this response. */
    public int size() {
        return values.size();
    }

    /** Raw driver value of a statement result, for full manual control. */
    public Value value(int statementIndex) {
        return values.get(statementIndex);
    }

    /** Maps a statement's rows onto the given class (POJO or record). */
    public <T> List<T> list(int statementIndex, Class<T> type) {
        Value value = values.get(statementIndex);
        if (value.isNone() || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<T> rows = new ArrayList<>();
            for (Value row : value.getArray()) {
                rows.add(convert(row, type));
            }
            return rows;
        }
        return List.of(convert(value, type));
    }

    /** Maps a statement's rows with a custom mapper. */
    public <T> List<T> list(int statementIndex, ValueMapper<T> mapper) {
        Value value = values.get(statementIndex);
        if (value.isNone() || value.isNull()) {
            return List.of();
        }
        if (value.isArray()) {
            List<T> rows = new ArrayList<>();
            for (Value row : value.getArray()) {
                rows.add(mapper.map(row));
            }
            return rows;
        }
        return List.of(mapper.map(value));
    }

    /** First row of a statement result, if any. */
    public <T> Optional<T> first(int statementIndex, Class<T> type) {
        List<T> rows = list(statementIndex, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Single scalar result (e.g. {@code RETURN count()}); null for NONE/NULL. */
    public <T> T scalar(int statementIndex, Class<T> type) {
        Value value = values.get(statementIndex);
        if (value.isNone() || value.isNull()) {
            return null;
        }
        return convert(value, type);
    }

    /**
     * The driver's {@code Value.get(Class)} converter only handles object
     * shapes (POJOs and records); plain scalars need the typed accessors.
     */
    @SuppressWarnings("unchecked")
    private static <T> T convert(Value value, Class<T> type) {
        Object converted;
        if (type == String.class) {
            converted = value.getString();
        }
        else if (type == Long.class) {
            converted = value.getLong();
        }
        else if (type == Integer.class) {
            converted = (int) value.getLong();
        }
        else if (type == Double.class) {
            converted = value.getDouble();
        }
        else if (type == Boolean.class) {
            converted = value.getBoolean();
        }
        else if (type == BigDecimal.class) {
            converted = value.getBigDecimal();
        }
        else if (type == UUID.class) {
            converted = value.getUuid();
        }
        else if (type == Duration.class) {
            converted = value.getDuration();
        }
        else if (type == ZonedDateTime.class) {
            converted = value.getDateTime();
        }
        else if (type == RecordId.class) {
            converted = value.getRecordId();
        }
        else if (type == Value.class) {
            converted = value;
        }
        else {
            converted = value.get(type);
        }
        return (T) converted;
    }
}
