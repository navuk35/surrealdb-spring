package io.github.navuk35.surrealdb.spring.surge;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Composes the SurrealQL sent for one migration. The changelog record is
 * bundled into the same BEGIN/COMMIT request as the migration body, so
 * "applied" and "recorded" are a single atomic fact — a client crash
 * between the two can never leave an applied-but-unrecorded migration.
 *
 * <p>Bookkeeping parameters are prefixed {@code __surge_} so they can never
 * collide with parameters used by the migration author's own statements.
 */
final class SurgeStatements {

    /**
     * Opt-out directive for migrations that must not run inside a
     * transaction (huge backfills hitting backend transaction limits, or
     * DEFINE INDEX ... CONCURRENTLY whose build is asynchronous anyway).
     * Opting out also reopens the small applied-but-unrecorded crash window,
     * since the changelog record must then travel in a second request.
     */
    static final String NO_TRANSACTION_DIRECTIVE = "-- surge:no-transaction";

    private static final Pattern USER_MANAGED_TRANSACTION = Pattern.compile("(?i)\\bBEGIN\\b");

    /**
     * The execution time is only known after the request returns, but the
     * atomic record travels WITH the request — so it carries this marker and
     * is patched best-effort afterwards.
     */
    static final long EXECUTION_TIME_UNKNOWN = -1L;

    private SurgeStatements() {
    }

    static boolean runsInTransaction(String content) {
        return !content.stripLeading().startsWith(NO_TRANSACTION_DIRECTIVE)
                && !USER_MANAGED_TRANSACTION.matcher(content).find();
    }

    static Composed transactionalApply(MigrationScript script, long installedRank) {
        String versionClause = script.version() != null
                ? "version = $__surge_version"
                : "version = NONE";
        String sql = "BEGIN TRANSACTION;\n"
                + script.content()
                + "\nUPSERT type::record('surge_changelog', $__surge_script)"
                + " SET type = $__surge_type, " + versionClause + ","
                + " description = $__surge_description, script = $__surge_script,"
                + " checksum = $__surge_checksum, installed_rank = $__surge_rank,"
                + " installed_on = time::now(), execution_time_ms = $__surge_ms;\n"
                + "COMMIT TRANSACTION;";

        Map<String, Object> params = new HashMap<>();
        params.put("__surge_script", script.script());
        params.put("__surge_type", script.type().name());
        params.put("__surge_description", script.description());
        params.put("__surge_checksum", script.checksum());
        params.put("__surge_rank", installedRank);
        params.put("__surge_ms", EXECUTION_TIME_UNKNOWN);
        if (script.version() != null) {
            params.put("__surge_version", script.version().raw());
        }
        return new Composed(sql, params);
    }

    record Composed(String sql, Map<String, Object> params) {
    }
}
