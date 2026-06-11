package io.github.navuk35.surrealdb.spring.sample;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@Tag("integration")
abstract class AbstractSurrealIntegrationTest {

    // Driver 2.1.0 requires the SurrealDB 3.x WebSocket subprotocol — 2.x
    // servers fail the handshake with "Server sent no subprotocol".
    private static final String SURREAL_IMAGE = System.getProperty(
            "surrealdb.image", "surrealdb/surrealdb:v3.0.5");

    /**
     * Singleton container: started once for the whole test run and reaped by
     * Ryuk on JVM exit. Deliberately NOT annotated with
     * {@code @Testcontainers}/{@code @Container}, which would stop and
     * recreate the container (and with it the mapped port, the
     * {@code spring.surrealdb.url}, and therefore the cached Spring context)
     * for every test class.
     */
    @SuppressWarnings("resource")
    static final GenericContainer<?> SURREAL = new GenericContainer<>(
            DockerImageName.parse(SURREAL_IMAGE))
            .withExposedPorts(8000)
            .withCommand("start", "--bind", "0.0.0.0:8000", "--user", "root", "--pass", "root")
            .waitingFor(Wait.forHttp("/health")
                    .forPort(8000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static {
        SURREAL.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.surrealdb.url",
                () -> "ws://" + SURREAL.getHost() + ":" + SURREAL.getMappedPort(8000));
    }
}
