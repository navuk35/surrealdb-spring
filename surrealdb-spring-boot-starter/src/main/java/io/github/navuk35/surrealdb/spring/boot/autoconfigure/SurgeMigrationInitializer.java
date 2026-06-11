package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import io.github.navuk35.surrealdb.spring.surge.SurgeMigrator;
import io.github.navuk35.surrealdb.spring.surge.SurgeTenantMigrator;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;

/**
 * Runs Surge migrations during context startup. Beans that use the
 * database (SurrealTemplate, the transaction manager) are made to depend
 * on this initializer so the schema is migrated before they are used.
 * When tenants are configured, every tenant database is swept after the
 * primary database.
 */
public class SurgeMigrationInitializer implements InitializingBean {

    private final SurgeMigrator migrator;
    private final SurgeTenantMigrator tenantMigrator;
    private final List<String> tenants;

    public SurgeMigrationInitializer(SurgeMigrator migrator) {
        this(migrator, null, List.of());
    }

    public SurgeMigrationInitializer(SurgeMigrator migrator,
            SurgeTenantMigrator tenantMigrator, List<String> tenants) {
        this.migrator = migrator;
        this.tenantMigrator = tenantMigrator;
        this.tenants = tenants != null ? tenants : List.of();
    }

    @Override
    public void afterPropertiesSet() {
        migrator.migrate();
        if (tenantMigrator != null && !tenants.isEmpty()) {
            tenantMigrator.migrateAll(tenants);
        }
    }
}
