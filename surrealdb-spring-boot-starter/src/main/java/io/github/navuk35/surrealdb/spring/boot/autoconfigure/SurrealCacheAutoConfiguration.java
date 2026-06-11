package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surrealdb.Surreal;
import com.surrealdb.signin.RootCredential;
import io.github.navuk35.surrealdb.spring.cache.SurrealCacheConfiguration;
import io.github.navuk35.surrealdb.spring.cache.SurrealCacheManager;
import io.github.navuk35.surrealdb.spring.cache.SurrealCacheProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@AutoConfiguration
@ConditionalOnClass({Surreal.class, CacheManager.class})
@ConditionalOnProperty(prefix = "spring.surrealdb.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SurrealCacheProperties.class)
@Import(SurrealCacheConfiguration.class)
public class SurrealCacheAutoConfiguration {

    @Bean(destroyMethod = "close")
    @Qualifier("surrealCache")
    @ConditionalOnMissingBean(name = "surrealCache")
    Surreal surrealCache(SurrealCacheProperties properties) {
        Surreal db = new Surreal();
        if (properties.getMode() == SurrealCacheProperties.Mode.memory) {
            db.connect(embeddedCacheUrl());
        }
        else {
            String url = properties.getUrl();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException(
                        "spring.surrealdb.cache.url is required when spring.surrealdb.cache.mode=remote");
            }
            db.connect(url);
            db.signin(new RootCredential(properties.getUsername(), properties.getPassword()));
        }
        db.useNs(properties.getNamespace()).useDb(properties.getDatabase());
        return db;
    }

    @Bean
    @ConditionalOnMissingBean(SurrealCacheManager.class)
    SurrealCacheManager surrealCacheManager(
            @Qualifier("surrealCache") Surreal surrealCache,
            SurrealCacheProperties properties) {
        // The cache wire format must not depend on the application's Jackson
        // customizations, so the manager gets its own private mapper instead
        // of an ObjectMapper bean (which would also race with Boot's
        // JacksonAutoConfiguration for the @ConditionalOnMissingBean).
        return new SurrealCacheManager(surrealCache, new ObjectMapper(), properties.getDefaultTtl());
    }

    private static String embeddedCacheUrl() {
        try {
            Path directory = Files.createTempDirectory("surreal-cache-");
            return "surrealkv://" + directory.toAbsolutePath();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to create embedded cache directory", ex);
        }
    }
}
