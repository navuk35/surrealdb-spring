package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import com.surrealdb.Surreal;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = SurrealAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(Surreal.class)
public class SurrealHealthContributorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SurrealHealthIndicator surrealHealthIndicator(Surreal surreal) {
        return new SurrealHealthIndicator(surreal);
    }
}
