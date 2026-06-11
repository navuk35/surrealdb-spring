package io.github.navuk35.surrealdb.spring.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "spring.surrealdb.cache")
public class SurrealCacheProperties {

    private boolean enabled = true;
    private Mode mode = Mode.memory;
    private String url;
    private String namespace = "cache";
    private String database = "main";
    private String username = "root";
    private String password = "root";
    private Duration defaultTtl = Duration.ofMinutes(10);

    /**
     * Per-cache-name TTL overrides; ZERO means the cache never expires.
     */
    private Map<String, Duration> ttlPerCache = new HashMap<>();

    public enum Mode {
        memory, remote
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Map<String, Duration> getTtlPerCache() {
        return ttlPerCache;
    }

    public void setTtlPerCache(Map<String, Duration> ttlPerCache) {
        this.ttlPerCache = ttlPerCache;
    }
}
