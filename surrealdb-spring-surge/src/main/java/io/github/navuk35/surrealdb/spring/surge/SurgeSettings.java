package io.github.navuk35.surrealdb.spring.surge;

import java.time.Duration;
import java.util.List;

public record SurgeSettings(List<String> locations, Duration lockTimeout) {

    public static final String DEFAULT_LOCATION = "classpath:surge/changelog";

    public static SurgeSettings defaults() {
        return new SurgeSettings(List.of(DEFAULT_LOCATION), Duration.ofMinutes(1));
    }
}
