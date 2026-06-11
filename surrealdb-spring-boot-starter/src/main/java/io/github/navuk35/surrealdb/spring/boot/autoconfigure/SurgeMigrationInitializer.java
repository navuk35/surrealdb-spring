package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import io.github.navuk35.surrealdb.spring.surge.SurgeMigrator;
import org.springframework.beans.factory.InitializingBean;

/**
 * Runs Surge migrations during context startup. Beans that use the
 * database (SurrealTemplate, the transaction manager) are made to depend
 * on this initializer so the schema is migrated before they are used.
 */
public class SurgeMigrationInitializer implements InitializingBean {

    private final SurgeMigrator migrator;

    public SurgeMigrationInitializer(SurgeMigrator migrator) {
        this.migrator = migrator;
    }

    @Override
    public void afterPropertiesSet() {
        migrator.migrate();
    }
}
