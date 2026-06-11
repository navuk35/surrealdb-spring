package io.github.navuk35.surrealdb.spring.surge;

import com.surrealdb.Surreal;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Multi-tenant migrations for the database-per-tenant model: every tenant
 * is a SurrealDB database that receives the shared {@code common/}
 * changelog plus its own {@code tenants/<id>/} overlay. Each tenant
 * database keeps its own {@code surge_changelog} and {@code surge_lock},
 * so tenants migrate independently of one another.
 *
 * <pre>
 * surge/changelog/
 * ├── common/                 applied to EVERY tenant, in version order
 * └── tenants/
 *     └── acme/               applied ONLY to tenant 'acme'
 * </pre>
 *
 * <p>Every tenant migration runs on its own short-lived connection from the
 * {@link SurrealConnectionFactory} — never on a shared one, whose
 * {@code useDb()} state-switch would redirect live traffic mid-flight.
 */
public class SurgeTenantMigrator {

    private static final Log logger = LogFactory.getLog(SurgeTenantMigrator.class);

    /** Tenant ids become database names and changelog folder names. */
    private static final Pattern SAFE_TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private final SurrealConnectionFactory connectionFactory;
    private final String namespace;
    private final SurgeSettings baseSettings;

    public SurgeTenantMigrator(SurrealConnectionFactory connectionFactory, String namespace,
            SurgeSettings baseSettings) {
        this.connectionFactory = connectionFactory;
        this.namespace = namespace;
        this.baseSettings = baseSettings;
    }

    /** Migrates every tenant in order; fails fast naming the failing tenant. */
    public Map<String, SurgeResult> migrateAll(List<String> tenants) {
        Map<String, SurgeResult> results = new LinkedHashMap<>();
        for (String tenant : tenants) {
            results.put(tenant, migrateTenant(tenant));
        }
        return results;
    }

    /** Migrates one tenant — the runtime onboarding entry point. */
    public SurgeResult migrateTenant(String tenant) {
        if (tenant == null || !SAFE_TENANT.matcher(tenant).matches()) {
            throw new SurgeException("Invalid tenant id '" + tenant
                    + "' — tenant ids become database and folder names and must match "
                    + SAFE_TENANT.pattern());
        }
        logger.info("Surge: migrating tenant '" + tenant + "'");
        Surreal connection = connectionFactory.connect(namespace, tenant);
        try {
            SurgeMigrator migrator = new SurgeMigrator(connection, settingsFor(tenant));
            return migrator.migrate();
        }
        catch (SurgeException ex) {
            throw new SurgeException("Migration of tenant '" + tenant + "' failed: "
                    + ex.getMessage(), ex);
        }
        finally {
            connection.close();
        }
    }

    /** Effective changelog per tenant: union of common/ and tenants/<id>/. */
    private SurgeSettings settingsFor(String tenant) {
        List<String> locations = new ArrayList<>();
        for (String base : baseSettings.locations()) {
            String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            locations.add(root + "/common");
            locations.add(root + "/tenants/" + tenant);
        }
        return new SurgeSettings(locations, baseSettings.lockTimeout(), baseSettings.lockLease());
    }
}
