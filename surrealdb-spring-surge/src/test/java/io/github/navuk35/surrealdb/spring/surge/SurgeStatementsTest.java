package io.github.navuk35.surrealdb.spring.surge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SurgeStatementsTest {

    private MigrationScript versioned(String content) {
        return new MigrationScript(MigrationType.VERSIONED, MigrationVersion.parse("1_2"),
                "create things", "V1_2__create_things.surql", content, "abc123");
    }

    private MigrationScript repeatable(String content) {
        return new MigrationScript(MigrationType.REPEATABLE, null,
                "functions", "R__functions.surql", content, "def456");
    }

    @Test
    void transactionalApplyBundlesMigrationAndChangelogRecordAtomically() {
        SurgeStatements.Composed composed = SurgeStatements.transactionalApply(
                versioned("DEFINE TABLE IF NOT EXISTS thing SCHEMALESS;"), 7);

        String sql = composed.sql();
        int begin = sql.indexOf("BEGIN TRANSACTION;");
        int content = sql.indexOf("DEFINE TABLE IF NOT EXISTS thing");
        int record = sql.indexOf("UPSERT type::record('surge_changelog'");
        int commit = sql.indexOf("COMMIT TRANSACTION;");

        assertThat(begin).as("starts a transaction").isZero();
        assertThat(content).as("migration body inside the transaction").isGreaterThan(begin);
        assertThat(record).as("changelog record inside the SAME transaction").isGreaterThan(content);
        assertThat(commit).as("commit covers both").isGreaterThan(record);

        assertThat(composed.params())
                .containsEntry("__surge_script", "V1_2__create_things.surql")
                .containsEntry("__surge_type", "VERSIONED")
                .containsEntry("__surge_version", "1_2")
                .containsEntry("__surge_checksum", "abc123")
                .containsEntry("__surge_rank", 7L);
    }

    @Test
    void namespacedParamsCannotCollideWithMigrationAuthorsParams() {
        SurgeStatements.Composed composed = SurgeStatements.transactionalApply(
                versioned("CREATE thing SET note = 'uses $script and $version freely';"), 1);

        assertThat(composed.params().keySet())
                .allMatch(key -> key.startsWith("__surge_"));
    }

    @Test
    void repeatableRecordsVersionAsNone() {
        SurgeStatements.Composed composed = SurgeStatements.transactionalApply(
                repeatable("DEFINE FUNCTION OVERWRITE fn::x() { RETURN 1; };"), 3);

        assertThat(composed.sql()).contains("version = NONE");
        assertThat(composed.params()).doesNotContainKey("__surge_version");
    }

    @Test
    void directiveAndUserManagedTransactionsOptOut() {
        assertThat(SurgeStatements.runsInTransaction(
                "-- surge:no-transaction\nUPDATE big_table SET x = 1;")).isFalse();
        assertThat(SurgeStatements.runsInTransaction(
                "BEGIN; CREATE a; COMMIT;")).isFalse();
        assertThat(SurgeStatements.runsInTransaction(
                "DEFINE TABLE t SCHEMALESS;")).isTrue();
    }

    @Test
    void executionTimeIsUnknownAtComposeTimeAndMarkedAsSuch() {
        SurgeStatements.Composed composed = SurgeStatements.transactionalApply(
                versioned("DEFINE TABLE t SCHEMALESS;"), 1);

        // the real duration is only known after the request returns; the atomic
        // record carries -1 and is patched best-effort afterwards
        assertThat(composed.params()).containsEntry("__surge_ms", -1L);
    }
}
