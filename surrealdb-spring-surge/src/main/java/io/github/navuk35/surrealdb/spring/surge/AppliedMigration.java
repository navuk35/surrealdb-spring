package io.github.navuk35.surrealdb.spring.surge;

public record AppliedMigration(
        MigrationType type,
        MigrationVersion version,
        String script,
        String checksum,
        long installedRank) {
}
