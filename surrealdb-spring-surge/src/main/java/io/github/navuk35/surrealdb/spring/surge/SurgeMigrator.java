package io.github.navuk35.surrealdb.spring.surge;

import com.surrealdb.Response;
import com.surrealdb.Surreal;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Flyway-style migration runner for SurrealDB. Versioned migrations
 * ({@code V<version>__<description>.surql}) run exactly once in numeric
 * version order and are checksum-frozen afterwards; repeatable migrations
 * ({@code R__<description>.surql}) re-run whenever their content changes.
 */
public class SurgeMigrator {

    private static final Log logger = LogFactory.getLog(SurgeMigrator.class);

    /**
     * Opt-out directive for migrations that must not run inside a
     * transaction (e.g. huge backfills hitting backend transaction limits,
     * or DEFINE INDEX ... CONCURRENTLY whose build is asynchronous anyway).
     */
    static final String NO_TRANSACTION_DIRECTIVE = "-- surge:no-transaction";

    private static final Pattern USER_MANAGED_TRANSACTION =
            Pattern.compile("(?i)\\bBEGIN\\b");

    private final Surreal surreal;
    private final SurgeSettings settings;
    private final MigrationScanner scanner = new MigrationScanner();
    private final SurgeChangelog changelog;

    public SurgeMigrator(Surreal surreal) {
        this(surreal, SurgeSettings.defaults());
    }

    public SurgeMigrator(Surreal surreal, SurgeSettings settings) {
        this.surreal = surreal;
        this.settings = settings;
        this.changelog = new SurgeChangelog(surreal);
    }

    public SurgeResult migrate() {
        MigrationScanner.ScanResult scripts = scanner.scan(settings.locations());
        changelog.ensureSchema();
        changelog.acquireLock(settings.lockTimeout());
        try {
            List<AppliedMigration> applied = changelog.findApplied();
            validate(scripts, applied);

            long rank = applied.stream().mapToLong(AppliedMigration::installedRank).max().orElse(0);
            int versionedApplied = 0;
            int repeatableApplied = 0;

            for (MigrationScript pending : pendingVersioned(scripts, applied)) {
                apply(pending, ++rank);
                versionedApplied++;
            }
            for (MigrationScript pending : pendingRepeatable(scripts, applied)) {
                apply(pending, ++rank);
                repeatableApplied++;
            }

            if (versionedApplied + repeatableApplied == 0) {
                logger.info("Surge: schema is up to date, no migrations to apply");
            }
            return new SurgeResult(versionedApplied, repeatableApplied);
        }
        finally {
            changelog.releaseLock();
        }
    }

    private void validate(MigrationScanner.ScanResult scripts, List<AppliedMigration> applied) {
        Map<String, AppliedMigration> appliedByScript = new HashMap<>();
        for (AppliedMigration migration : applied) {
            appliedByScript.put(migration.script(), migration);
        }
        for (MigrationScript script : scripts.versioned()) {
            AppliedMigration existing = appliedByScript.remove(script.script());
            if (existing != null && !existing.checksum().equals(script.checksum())) {
                throw new SurgeException("Checksum mismatch for applied migration '"
                        + script.script() + "' — versioned migrations are frozen once applied. "
                        + "Add a new V migration for the change, or use an R__ repeatable "
                        + "migration for definitions that should evolve in place.");
            }
        }
        for (MigrationScript script : scripts.repeatable()) {
            appliedByScript.remove(script.script());
        }
        for (AppliedMigration missing : appliedByScript.values()) {
            logger.warn("Surge: applied migration '" + missing.script()
                    + "' no longer exists in " + settings.locations());
        }
    }

    private List<MigrationScript> pendingVersioned(MigrationScanner.ScanResult scripts,
            List<AppliedMigration> applied) {
        MigrationVersion maxApplied = applied.stream()
                .filter(m -> m.type() == MigrationType.VERSIONED)
                .map(AppliedMigration::version)
                .max(MigrationVersion::compareTo)
                .orElse(null);
        Map<String, AppliedMigration> appliedByScript = new HashMap<>();
        applied.forEach(m -> appliedByScript.put(m.script(), m));

        List<MigrationScript> pending = scripts.versioned().stream()
                .filter(script -> !appliedByScript.containsKey(script.script()))
                .toList();
        for (MigrationScript script : pending) {
            if (maxApplied != null && script.version().compareTo(maxApplied) < 0) {
                throw new SurgeException("Out-of-order migration '" + script.script()
                        + "': version " + script.version() + " is below the latest applied "
                        + "version " + maxApplied + ". Renumber it above " + maxApplied + ".");
            }
        }
        return pending;
    }

    private List<MigrationScript> pendingRepeatable(MigrationScanner.ScanResult scripts,
            List<AppliedMigration> applied) {
        Map<String, String> appliedChecksums = new HashMap<>();
        applied.stream()
                .filter(m -> m.type() == MigrationType.REPEATABLE)
                .forEach(m -> appliedChecksums.put(m.script(), m.checksum()));
        return scripts.repeatable().stream()
                .filter(script -> !script.checksum().equals(appliedChecksums.get(script.script())))
                .toList();
    }

    private void apply(MigrationScript script, long rank) {
        logger.info("Surge: applying " + script.script());
        long started = System.nanoTime();
        try {
            Response response = surreal.query(executableSql(script));
            // statement errors only surface when the result slot is consumed
            for (int i = 0; i < response.size(); i++) {
                response.take(i);
            }
        }
        catch (RuntimeException ex) {
            throw new SurgeException("Migration '" + script.script() + "' failed: "
                    + ex.getMessage(), ex);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        changelog.record(script, elapsedMs, rank);
    }

    private String executableSql(MigrationScript script) {
        String content = script.content();
        if (content.stripLeading().startsWith(NO_TRANSACTION_DIRECTIVE)
                || USER_MANAGED_TRANSACTION.matcher(content).find()) {
            return content;
        }
        // SurrealDB 3.x DDL is transactional, so each migration is atomic
        return "BEGIN TRANSACTION;\n" + content + "\nCOMMIT TRANSACTION;";
    }
}
