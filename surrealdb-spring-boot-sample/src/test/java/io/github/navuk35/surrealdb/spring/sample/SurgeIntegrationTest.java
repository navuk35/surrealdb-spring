package io.github.navuk35.surrealdb.spring.sample;

import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import io.github.navuk35.surrealdb.spring.surge.SurgeException;
import io.github.navuk35.surrealdb.spring.surge.SurgeMigrator;
import io.github.navuk35.surrealdb.spring.surge.SurgeResult;
import io.github.navuk35.surrealdb.spring.surge.SurgeSettings;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SurgeIntegrationTest extends AbstractSurrealIntegrationTest {

    @Autowired
    private SurrealTemplate template;

    @Autowired
    private SurgeMigrator startupMigrator;

    @Test
    @Order(1)
    void startupAppliesAllChangelogMigrations() {
        // person table from V0_1 is usable, schema from V0_2 is enforced
        template.query("CREATE person SET name = 'Navin', email = 'navin@example.com'");
        assertThatThrownBy(() -> template
                .query("CREATE person SET name = 'Dup', email = 'navin@example.com'"))
                .as("unique index from V0_2 must reject duplicate emails")
                .isInstanceOf(Exception.class);

        // function from the repeatable migration is callable
        assertThat(template.query("RETURN fn::greet_person('Navin')").scalar(0, String.class))
                .isEqualTo("Hello, Navin");

        assertThat(changelogScripts()).containsExactlyInAnyOrder(
                "V0_1__create_person.surql",
                "V0_2__person_email_unique.surql",
                "R__person_functions.surql");
    }

    @Test
    @Order(2)
    void migrateAgainAppliesNothing() {
        SurgeResult result = startupMigrator.migrate();

        assertThat(result.totalApplied()).isZero();
    }

    @Test
    @Order(3)
    void versionedMigrationIsFrozenOnceApplied(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V9_1__frozen_table.surql"),
                "DEFINE TABLE IF NOT EXISTS frozen_thing SCHEMALESS;");
        SurgeMigrator migrator = migratorFor(dir);

        assertThat(migrator.migrate().versionedApplied()).isEqualTo(1);

        Files.writeString(dir.resolve("V9_1__frozen_table.surql"),
                "DEFINE TABLE IF NOT EXISTS frozen_thing_edited SCHEMALESS;");
        assertThatThrownBy(migrator::migrate)
                .isInstanceOf(SurgeException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    @Order(4)
    void repeatableMigrationReRunsWhenEdited(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("R__answer_function.surql"),
                "DEFINE FUNCTION OVERWRITE fn::answer() { RETURN 1; };");
        SurgeMigrator migrator = migratorFor(dir);

        assertThat(migrator.migrate().repeatableApplied()).isEqualTo(1);
        assertThat(answer()).isEqualTo(1);

        // unchanged file does not re-run
        assertThat(migrator.migrate().repeatableApplied()).isZero();

        // edited file re-runs and the new definition wins
        Files.writeString(dir.resolve("R__answer_function.surql"),
                "DEFINE FUNCTION OVERWRITE fn::answer() { RETURN 42; };");
        assertThat(migrator.migrate().repeatableApplied()).isEqualTo(1);
        assertThat(answer()).isEqualTo(42);
    }

    @Test
    @Order(5)
    void laterVersionsArePickedUpOnNextRun(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V9_5__first.surql"),
                "DEFINE TABLE IF NOT EXISTS later_one SCHEMALESS;");
        SurgeMigrator migrator = migratorFor(dir);
        assertThat(migrator.migrate().versionedApplied()).isEqualTo(1);

        Files.writeString(dir.resolve("V9_6__second.surql"),
                "DEFINE TABLE IF NOT EXISTS later_two SCHEMALESS;");
        assertThat(migrator.migrate().versionedApplied()).isEqualTo(1);

        assertThat(changelogScripts())
                .contains("V9_5__first.surql", "V9_6__second.surql");
    }

    @Test
    @Order(6)
    void failedMigrationIsAtomicAndNotRecorded(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V9_9__broken.surql"),
                """
                CREATE atomic_check SET marker = 'must not survive';
                THROW "boom";
                """);
        SurgeMigrator migrator = migratorFor(dir);

        assertThatThrownBy(migrator::migrate)
                .isInstanceOf(SurgeException.class)
                .hasMessageContaining("V9_9__broken.surql");

        assertThat(atomicCheckRowCount())
                .as("statements before the failure must be rolled back")
                .isZero();
        assertThat(changelogScripts()).doesNotContain("V9_9__broken.surql");
    }

    private SurgeMigrator migratorFor(Path dir) {
        return new SurgeMigrator(template.getSurreal(),
                new SurgeSettings(List.of("file:" + dir), Duration.ofSeconds(10)));
    }

    private long answer() {
        return template.query("RETURN fn::answer()").scalar(0, Long.class);
    }

    private long atomicCheckRowCount() {
        try {
            return template.query("SELECT * FROM atomic_check")
                    .list(0, value -> value).size();
        }
        catch (RuntimeException ex) {
            // the rollback discarded even the implicitly created table
            return 0;
        }
    }

    private List<String> changelogScripts() {
        return template.query("SELECT script FROM surge_changelog")
                .list(0, row -> row.getObject().get("script").getString());
    }
}
