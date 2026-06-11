package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import com.surrealdb.Surreal;
import com.surrealdb.signin.RootCredential;
import io.github.navuk35.surrealdb.spring.core.SurrealExceptionTranslator;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import io.github.navuk35.surrealdb.spring.core.SurrealTransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@AutoConfiguration
@ConditionalOnClass(Surreal.class)
@ConditionalOnProperty(prefix = "spring.surrealdb", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SurrealProperties.class)
@EnableTransactionManagement
public class SurrealAutoConfiguration {

    @Bean(destroyMethod = "close")
    @Primary
    @ConditionalOnMissingBean
    Surreal surreal(SurrealProperties properties) {
        Surreal db = new Surreal();
        db.connect(properties.getUrl());
        db.useNs(properties.getNamespace()).useDb(properties.getDatabase());
        db.signin(new RootCredential(properties.getUsername(), properties.getPassword()));
        return db;
    }

    @Bean
    @ConditionalOnMissingBean
    SurrealExceptionTranslator surrealExceptionTranslator() {
        return new SurrealExceptionTranslator();
    }

    @Bean
    @ConditionalOnMissingBean
    SurrealTemplate surrealTemplate(Surreal surreal, SurrealExceptionTranslator exceptionTranslator) {
        return new SurrealTemplate(surreal, exceptionTranslator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "surrealTransactionManager")
    PlatformTransactionManager surrealTransactionManager(Surreal surreal) {
        return new SurrealTransactionManager(surreal);
    }
}
