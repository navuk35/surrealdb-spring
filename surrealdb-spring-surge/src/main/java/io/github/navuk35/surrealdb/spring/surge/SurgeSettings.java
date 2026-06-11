package io.github.navuk35.surrealdb.spring.surge;

import java.time.Duration;
import java.util.List;

/**
 * @param locations   classpath:/file: folders scanned for .surql migrations
 * @param lockTimeout how long to wait for another instance's lock
 * @param lockLease   how long a held lock stays valid without a heartbeat
 *                    renewal — an expired lease is treated as a crashed
 *                    holder and stolen
 */
public record SurgeSettings(List<String> locations, Duration lockTimeout, Duration lockLease) {

    public static final String DEFAULT_LOCATION = "classpath:surge/changelog";

    public SurgeSettings(List<String> locations, Duration lockTimeout) {
        this(locations, lockTimeout, Duration.ofMinutes(5));
    }

    public static SurgeSettings defaults() {
        return new SurgeSettings(List.of(DEFAULT_LOCATION), Duration.ofMinutes(1));
    }
}
