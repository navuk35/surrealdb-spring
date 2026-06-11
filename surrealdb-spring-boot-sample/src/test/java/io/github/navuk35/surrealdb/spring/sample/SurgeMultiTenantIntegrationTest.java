package io.github.navuk35.surrealdb.spring.sample;

import com.surrealdb.Surreal;
import com.surrealdb.signin.RootCredential;
import io.github.navuk35.surrealdb.spring.surge.SurgeException;
import io.github.navuk35.surrealdb.spring.surge.SurgeResult;
import io.github.navuk35.surrealdb.spring.surge.SurgeSettings;
import io.github.navuk35.surrealdb.spring.surge.SurgeTenantMigrator;
import io.github.navuk35.surrealdb.spring.surge.SurrealConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SurgeMultiTenantIntegrationTest extends AbstractSurrealIntegrationTest {

    private final SurrealConnectionFactory factory = (namespace, database) -> {
        Surreal db = new Surreal();
        db.connect("ws://" + SURREAL.getHost() + ":" + SURREAL.getMappedPort(8000));
        db.useNs(namespace).useDb(database);
        db.signin(new RootCredential("root", "root"));
        return db;
    };

    @TempDir
    Path changelog;

    @BeforeEach
    void layoutChangelog() throws IOException {
        Files.createDirectories(changelog.resolve("common"));
        Files.createDirectories(changelog.resolve("tenants/acme"));
        Files.createDirectories(changelog.resolve("tenants/globex"));
        Files.writeString(changelog.resolve("common/V0_1__shared_schema.surql"),
                "DEFINE TABLE IF NOT EXISTS customer SCHEMALESS;");
        Files.writeString(changelog.resolve("tenants/acme/V0_2__acme_extras.surql"),
                "DEFINE TABLE IF NOT EXISTS acme_audit SCHEMALESS;");
        Files.writeString(changelog.resolve("tenants/globex/V0_2__globex_extras.surql"),
                "DEFINE TABLE IF NOT EXISTS globex_billing SCHEMALESS;");
    }

    /** Each test gets its own namespace — tenant DBs persist in the shared container. */
    private SurgeTenantMigrator migrator(String namespace) {
        return new SurgeTenantMigrator(factory, namespace,
                new SurgeSettings(List.of("file:" + changelog), Duration.ofSeconds(10)));
    }

    @Test
    void everyTenantGetsCommonPlusOnlyItsOwnOverlay() {
        Map<String, SurgeResult> results = migrator("mt_basic").migrateAll(List.of("acme", "globex"));

        assertThat(results.get("acme").versionedApplied()).isEqualTo(2);   // common + own overlay
        assertThat(results.get("globex").versionedApplied()).isEqualTo(2);

        assertThat(tables("mt_basic", "acme"))
                .contains("customer", "acme_audit").doesNotContain("globex_billing");
        assertThat(tables("mt_basic", "globex"))
                .contains("customer", "globex_billing").doesNotContain("acme_audit");
    }

    @Test
    void tenantsKeepIndependentChangelogs() {
        migrator("mt_changelog").migrateAll(List.of("acme", "globex"));

        assertThat(changelogScripts("mt_changelog", "acme"))
                .containsExactlyInAnyOrder("V0_1__shared_schema.surql", "V0_2__acme_extras.surql");
        assertThat(changelogScripts("mt_changelog", "globex"))
                .containsExactlyInAnyOrder("V0_1__shared_schema.surql", "V0_2__globex_extras.surql");
    }

    @Test
    void onboardingANewTenantAtRuntimeAppliesCommonChangelog() {
        migrator("mt_onboard").migrateAll(List.of("acme"));

        // the provisioning flow: a brand-new tenant joins while the app runs
        SurgeResult onboarded = migrator("mt_onboard").migrateTenant("newco");

        assertThat(onboarded.versionedApplied()).isEqualTo(1);   // common only, no overlay
        assertThat(tables("mt_onboard", "newco")).contains("customer");
    }

    @Test
    void reRunningTheSweepIsIdempotentPerTenant() {
        migrator("mt_idem").migrateAll(List.of("acme", "globex"));
        Map<String, SurgeResult> second = migrator("mt_idem").migrateAll(List.of("acme", "globex"));

        assertThat(second.values()).allMatch(result -> result.totalApplied() == 0);
    }

    @Test
    void aFailingTenantIsNamedInTheError() throws IOException {
        // genuinely invalid: DEFINE TABLE requires a name
        Files.writeString(changelog.resolve("tenants/globex/V0_3__broken.surql"),
                "DEFINE TABLE;");

        assertThatThrownBy(() -> migrator("mt_fail").migrateAll(List.of("acme", "globex")))
                .isInstanceOf(SurgeException.class)
                .hasMessageContaining("globex");
    }

    @Test
    void invalidTenantNamesAreRejectedBeforeTouchingAnything() {
        assertThatThrownBy(() -> migrator("mt_guard").migrateTenant("../evil"))
                .isInstanceOf(SurgeException.class);
    }

    private List<String> tables(String namespace, String tenant) {
        Surreal db = factory.connect(namespace, tenant);
        try {
            List<String> names = new java.util.ArrayList<>();
            db.query("INFO FOR DB").take(0).getObject().get("tables").getObject()
                    .forEach(entry -> names.add(entry.getKey()));
            return names;
        }
        finally {
            db.close();
        }
    }

    private List<String> changelogScripts(String namespace, String tenant) {
        Surreal db = factory.connect(namespace, tenant);
        try {
            List<String> scripts = new java.util.ArrayList<>();
            db.query("SELECT VALUE script FROM surge_changelog").take(0).getArray()
                    .forEach(value -> scripts.add(value.getString()));
            return scripts;
        }
        finally {
            db.close();
        }
    }
}
