package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import io.github.navuk35.surrealdb.spring.surge.SurgeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "spring.surrealdb.surge")
public class SurgeProperties {

    /**
     * Whether to run Surge migrations on application startup.
     */
    private boolean enabled = true;

    /**
     * Locations to scan for .surql migrations (classpath: or file:).
     */
    private List<String> locations = List.of(SurgeSettings.DEFAULT_LOCATION);

    /**
     * How long to wait for the migration lock held by another instance.
     */
    private Duration lockTimeout = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = locations;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }
}
