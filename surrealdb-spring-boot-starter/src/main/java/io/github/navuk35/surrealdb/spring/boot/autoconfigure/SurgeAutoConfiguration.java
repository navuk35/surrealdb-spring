package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import com.surrealdb.Surreal;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import io.github.navuk35.surrealdb.spring.surge.SurgeMigrator;
import io.github.navuk35.surrealdb.spring.surge.SurgeSettings;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ObjectUtils;

@AutoConfiguration(after = SurrealAutoConfiguration.class)
@ConditionalOnClass({Surreal.class, SurgeMigrator.class})
@ConditionalOnProperty(prefix = "spring.surrealdb.surge", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SurgeProperties.class)
public class SurgeAutoConfiguration {

    static final String INITIALIZER_BEAN_NAME = "surgeMigrationInitializer";

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Surreal.class)
    SurgeMigrator surgeMigrator(Surreal surreal, SurgeProperties properties) {
        return new SurgeMigrator(surreal, new SurgeSettings(
                properties.getLocations(), properties.getLockTimeout(), properties.getLockLease()));
    }

    @Bean(name = INITIALIZER_BEAN_NAME)
    @ConditionalOnMissingBean
    @ConditionalOnBean(SurgeMigrator.class)
    SurgeMigrationInitializer surgeMigrationInitializer(SurgeMigrator migrator) {
        return new SurgeMigrationInitializer(migrator);
    }

    /**
     * Makes database-using beans depend on the migration initializer so the
     * schema is up to date before anything queries it — the same trick
     * Spring Boot's Flyway auto-configuration plays for the DataSource.
     */
    @Bean
    static BeanFactoryPostProcessor surrealDependsOnSurgePostProcessor() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition(INITIALIZER_BEAN_NAME)) {
                return;
            }
            addDependsOn(beanFactory, SurrealTemplate.class);
            addDependsOn(beanFactory, PlatformTransactionManager.class);
        };
    }

    private static void addDependsOn(ConfigurableListableBeanFactory beanFactory, Class<?> type) {
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            String[] dependsOn = ObjectUtils.addObjectToArray(
                    definition.getDependsOn(), INITIALIZER_BEAN_NAME);
            definition.setDependsOn(dependsOn);
        }
    }
}
