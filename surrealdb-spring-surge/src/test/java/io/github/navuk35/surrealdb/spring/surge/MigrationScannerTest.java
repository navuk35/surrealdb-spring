package io.github.navuk35.surrealdb.spring.surge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationScannerTest {

    private final MigrationScanner scanner = new MigrationScanner();

    @Test
    void scansClasspathLocationAndOrdersVersionsNumerically() {
        MigrationScanner.ScanResult result = scanner.scan(List.of("classpath:surge-test/good"));

        assertThat(result.versioned())
                .extracting(MigrationScript::script)
                .containsExactly(
                        "V0_1__create_users.surql",
                        "V0_2__add_email.surql",
                        "V0_10__add_status.surql");
        assertThat(result.versioned().get(0).description()).isEqualTo("create users");
        assertThat(result.versioned().get(0).type()).isEqualTo(MigrationType.VERSIONED);
    }

    @Test
    void scansRepeatableMigrations() {
        MigrationScanner.ScanResult result = scanner.scan(List.of("classpath:surge-test/good"));

        assertThat(result.repeatable())
                .extracting(MigrationScript::script)
                .containsExactly("R__functions.surql");
        assertThat(result.repeatable().get(0).version()).isNull();
        assertThat(result.repeatable().get(0).type()).isEqualTo(MigrationType.REPEATABLE);
    }

    @Test
    void checksumIsStableAcrossLineEndings(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V1__a.surql"), "DEFINE TABLE a;\nDEFINE TABLE b;\n");
        MigrationScanner.ScanResult unix = scanner.scan(List.of("file:" + dir));

        Files.writeString(dir.resolve("V1__a.surql"), "DEFINE TABLE a;\r\nDEFINE TABLE b;\r\n");
        MigrationScanner.ScanResult windows = scanner.scan(List.of("file:" + dir));

        assertThat(unix.versioned().get(0).checksum())
                .isEqualTo(windows.versioned().get(0).checksum());
    }

    @Test
    void missingLocationYieldsNoMigrations() {
        MigrationScanner.ScanResult result = scanner.scan(List.of("classpath:surge-test/does-not-exist"));

        assertThat(result.versioned()).isEmpty();
        assertThat(result.repeatable()).isEmpty();
    }

    @Test
    void duplicateVersionsFail(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("V1__a.surql"), "DEFINE TABLE a;");
        Files.writeString(dir.resolve("V1_0__b.surql"), "DEFINE TABLE b;");

        assertThatThrownBy(() -> scanner.scan(List.of("file:" + dir)))
                .isInstanceOf(SurgeException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void invalidFileNameFails(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Vx__broken.surql"), "DEFINE TABLE a;");

        assertThatThrownBy(() -> scanner.scan(List.of("file:" + dir)))
                .isInstanceOf(SurgeException.class)
                .hasMessageContaining("Vx__broken.surql");
    }
}
