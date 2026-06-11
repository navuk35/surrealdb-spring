package io.github.navuk35.surrealdb.spring.surge;

import com.surrealdb.Response;
import com.surrealdb.Surreal;
import com.surrealdb.Value;

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
 */
class SurgeChangelog {

    private final Surreal surreal;

    SurgeChangelog(Surreal surreal) {
        this.surreal = surreal;
    }

    void ensureSchema() {
        surreal.query("""
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
                """);
    }

    List<AppliedMigration> findApplied() {
        Response response = surreal.query(
                "SELECT * FROM surge_changelog ORDER BY installed_rank ASC");
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
            surreal.query("""
                    UPSERT type::record('surge_changelog', $script)
                    SET type = $type, version = $version, description = $description,
                        script = $script, checksum = $checksum, installed_rank = $rank,
                        installed_on = time::now(), execution_time_ms = $ms
                    """, params);
        }
        else {
            surreal.query("""
                    UPSERT type::record('surge_changelog', $script)
                    SET type = $type, version = NONE, description = $description,
                        script = $script, checksum = $checksum, installed_rank = $rank,
                        installed_on = time::now(), execution_time_ms = $ms
                    """, params);
        }
    }

    void acquireLock(Duration timeout) {
        String owner = ManagementFactory.getRuntimeMXBean().getName();
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            try {
                surreal.query("""
                        CREATE surge_lock:global
                        SET locked_at = time::now(), locked_by = $owner
                        """, Map.of("owner", owner));
                return;
            }
            catch (RuntimeException ex) {
                if (Instant.now().isAfter(deadline)) {
                    throw new SurgeException("Could not acquire Surge migration lock within "
                            + timeout + " — is another instance migrating, or is a stale "
                            + "surge_lock:global record left over from a crash?", ex);
                }
                sleepQuietly();
            }
        }
    }

    void releaseLock() {
        surreal.query("DELETE surge_lock:global");
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
