package io.github.navuk35.surrealdb.spring.boot.autoconfigure;

import com.surrealdb.Surreal;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public class SurrealHealthIndicator implements HealthIndicator {

    private final Surreal surreal;

    public SurrealHealthIndicator(Surreal surreal) {
        this.surreal = surreal;
    }

    @Override
    public Health health() {
        try {
            if (surreal.health()) {
                return Health.up().withDetail("version", surreal.version()).build();
            }
            return Health.down().withDetail("version", surreal.version()).build();
        }
        catch (Exception ex) {
            return Health.down().withDetail("error", ex.getMessage()).build();
        }
    }
}
