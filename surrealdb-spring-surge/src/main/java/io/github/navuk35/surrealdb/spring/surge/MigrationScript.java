package io.github.navuk35.surrealdb.spring.surge;

public record MigrationScript(
        MigrationType type,
        MigrationVersion version,
        String description,
        String script,
        String content,
        String checksum) {
}
