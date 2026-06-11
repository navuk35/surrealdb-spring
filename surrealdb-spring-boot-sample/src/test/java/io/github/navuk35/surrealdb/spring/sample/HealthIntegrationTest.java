package io.github.navuk35.surrealdb.spring.sample;

import io.github.navuk35.surrealdb.spring.boot.autoconfigure.SurrealHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HealthIntegrationTest extends AbstractSurrealIntegrationTest {

    @Autowired
    private SurrealHealthIndicator healthIndicator;

    @Test
    void surrealHealthIsUp() {
        Health health = healthIndicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsKey("version");
    }
}
