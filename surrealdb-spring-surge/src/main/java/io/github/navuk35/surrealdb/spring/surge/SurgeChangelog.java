package io.github.navuk35.surrealdb.spring.surge;

import com.surrealdb.Response;
import com.surrealdb.Surreal;
import com.surrealdb.Value;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence of memory: tracks which migrations were applied in the
 * {@code surge_changelog} table and guards concurrent runs with a single
 * {@code surge_lock:global} record.
 *
 * <p>The lock is leased: it carries an {@code expires_at} that the holder
 * keeps extending via heartbeat while migrating. A lock whose lease has
 * expired is treated as left behind by a crashed holder and stolen, so a
 * {@code kill -9} mid-migration cannot block deployments forever.
 *
 * <p>All queries synchronize on the shared {@link Surreal} client because
 * the heartbeat thread runs concurrently with migration statements.
 */
class SurrealLockOwner {
    static final String ID = ManagementFactory.getRuntimeMXBean().getName();
}

class SurgeChangelog {

    private static final Log logger = LogFactory.getLog(SurgeChangelog.class);

    private final Surreal surreal;

    SurgeChangelog(Surreal surreal) {
        this.surreal = surreal;
    }

    void ensureSchema() {
        exec("""
                DEFINE TABLE IF NOT EXISTS surge_changelog SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS type ON surge_changelog TYPE string;
                DEFINE FIELD IF NOT EXISTS version ON surge_changelog TYPE option<string>;
                DEFINE FIELD IF NOT EXISTS description ON surge_changelog TYPE string;
                DEFINE FIELD IF NOT EXISTS script ON surge_changelog TYPE string;
                DEFINE FIELD IF NOT EXISTS checksum ON surge_changelog TYPE string;
                DEFINE FIELD IF NOT EXISTS installed_rank ON surge_changelog TYPE number;
                DEFINE FIELD IF NOT EXISTS installed_on ON surge_changelog TYPE datetime;
                DEFINE FIELD IF NOT EXISTS execution_time_ms ON surge_changelog TYPE number;
                DEFINE INDEX IF NOT EXISTS surge_changelog_script ON surge_changelog FIELDS script UNIQUE;
                DEFINE TABLE IF NOT EXISTS surge_lock SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS locked_at ON surge_lock TYPE datetime;
                DEFINE FIELD IF NOT EXISTS locked_by ON surge_lock TYPE string;
                DEFINE FIELD IF NOT EXISTS expires_at ON surge_lock TYPE option<datetime>;
                """);
    }

    List<AppliedMigration> findApplied() {
        Response response = query("SELECT * FROM surge_changelog ORDER BY installed_rank ASC");
        Value result = response.take(0);
        List<AppliedMigration> applied = new ArrayList<>();
        if (!result.isArray()) {
            return applied;
        }
        for (Value row : result.getArray()) {
            if (!row.isObject()) {
                continue;
            }
            com.surrealdb.Object entry = row.getObject();
            String rawVersion = optionalString(entry.get("version"));
            applied.add(new AppliedMigration(
                    MigrationType.valueOf(entry.get("type").getString()),
                    rawVersion != null ? MigrationVersion.parse(rawVersion) : null,
                    entry.get("script").getString(),
                    entry.get("checksum").getString(),
                    entry.get("installed_rank").getLong()));
        }
        return applied;
    }

    void record(MigrationScript script, long executionTimeMs, long installedRank) {
        Map<String, Object> params = new HashMap<>();
        params.put("script", script.script());
        params.put("type", script.type().name());
        params.put("description", script.description());
        params.put("checksum", script.checksum());
        params.put("rank", installedRank);
        params.put("ms", executionTimeMs);
        if (script.version() != null) {
            params.put("version", script.version().raw());
            exec("""
                    UPSERT type::record('surge_changelog', $script)
                    SET type = $type, version = $version, description = $description,
                        script = $script, checksum = $checksum, installed_rank = $rank,
                        installed_on = time::now(), execution_time_ms = $ms
                    """, params);
        }
        else {
            exec("""
                    UPSERT type::record('surge_changelog', $script)
                    SET type = $type, version = NONE, description = $description,
                        script = $script, checksum = $checksum, installed_rank = $rank,
                        installed_on = time::now(), execution_time_ms = $ms
                    """, params);
        }
    }

    /** Best-effort patch of the duration once the atomic apply has returned. */
    void updateExecutionTime(String script, long executionTimeMs) {
        exec("""
                UPDATE type::record('surge_changelog', $script)
                SET execution_time_ms = $ms
                """, Map.of("script", script, "ms", executionTimeMs));
    }

    void acquireLock(Duration timeout, Duration lease) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            try {
                exec("""
                        CREATE surge_lock:global
                        SET locked_at = time::now(), locked_by = $owner,
                            expires_at = time::now() + $lease
                        """, Map.of("owner", SurrealLockOwner.ID, "lease", lease));
                return;
            }
            catch (RuntimeException ex) {
                if (stealExpiredLock(lease)) {
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new SurgeException("Could not acquire Surge migration lock within "
                            + timeout + " — another instance is migrating and its lease "
                            + "has not expired", ex);
                }
                sleepQuietly();
            }
        }
    }

    /**
     * Atomic takeover of a lock whose lease has run out: the UPDATE's WHERE
     * makes check and claim a single operation, so two stealing instances
     * cannot both succeed.
     */
    private boolean stealExpiredLock(Duration lease) {
        Response response = query("""
                UPDATE surge_lock:global
                SET locked_by = $owner, locked_at = time::now(),
                    expires_at = time::now() + $lease
                WHERE expires_at = NONE OR expires_at < time::now()
                RETURN AFTER
                """, Map.of("owner", SurrealLockOwner.ID, "lease", lease));
        Value result = response.take(0);
        boolean stolen = result.isArray() && result.getArray().len() > 0;
        if (stolen) {
            logger.warn("Surge: stole migration lock whose lease had expired — "
                    + "previous holder likely crashed");
        }
        return stolen;
    }

    /** Heartbeat: keep the lease alive while this holder is still working. */
    void extendLease(Duration lease) {
        exec("""
                UPDATE surge_lock:global SET expires_at = time::now() + $lease
                WHERE locked_by = $owner
                """, Map.of("owner", SurrealLockOwner.ID, "lease", lease));
    }

    void releaseLock() {
        // only our own lock: never delete one another instance stole meanwhile
        exec("DELETE surge_lock:global WHERE locked_by = $owner",
                Map.of("owner", SurrealLockOwner.ID));
    }

    /**
     * Executes and DRAINS every result slot: the driver reports statement
     * errors lazily on take(), so an unconsumed response swallows failures —
     * a CREATE on an existing lock record must throw, not silently no-op.
     * Use for statements whose results are not needed.
     */
    void exec(String sql) {
        drain(query(sql));
    }

    void exec(String sql, Map<String, ?> params) {
        drain(query(sql, params));
    }

    private static void drain(Response response) {
        for (int i = 0; i < response.size(); i++) {
            response.take(i);
        }
    }

    Response query(String sql) {
        synchronized (surreal) {
            return surreal.query(sql);
        }
    }

    Response query(String sql, Map<String, ?> params) {
        synchronized (surreal) {
            return surreal.query(sql, params);
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(500);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SurgeException("Interrupted while waiting for the Surge migration lock", ex);
        }
    }

    private static String optionalString(Value value) {
        if (value == null || value.isNone() || value.isNull()) {
            return null;
        }
        return value.getString();
    }
}
