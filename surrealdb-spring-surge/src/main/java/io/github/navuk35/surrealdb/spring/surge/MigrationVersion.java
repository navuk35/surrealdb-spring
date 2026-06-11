package io.github.navuk35.surrealdb.spring.surge;

import java.util.Arrays;

public final class MigrationVersion implements Comparable<MigrationVersion> {

    private final long[] parts;
    private final String raw;

    private MigrationVersion(long[] parts, String raw) {
        this.parts = parts;
        this.raw = raw;
    }

    /**
     * Parses the version part of a migration file name, e.g. {@code 0_1} or
     * {@code 1_2_3}. Components are compared numerically, so {@code 0_10}
     * sorts after {@code 0_2}.
     */
    public static MigrationVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SurgeException("Migration version must not be empty");
        }
        String[] tokens = raw.split("_", -1);
        long[] parts = new long[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                parts[i] = Long.parseLong(tokens[i]);
            }
            catch (NumberFormatException ex) {
                throw new SurgeException("Invalid migration version '" + raw
                        + "': component '" + tokens[i] + "' is not a number");
            }
            if (parts[i] < 0) {
                throw new SurgeException("Invalid migration version '" + raw
                        + "': components must not be negative");
            }
        }
        return new MigrationVersion(parts, raw);
    }

    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(MigrationVersion other) {
        int length = Math.max(parts.length, other.parts.length);
        for (int i = 0; i < length; i++) {
            long mine = i < parts.length ? parts[i] : 0;
            long theirs = i < other.parts.length ? other.parts[i] : 0;
            int comparison = Long.compare(mine, theirs);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MigrationVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        // strip trailing zeros so 1 and 1_0 hash identically, matching equals
        int significant = parts.length;
        while (significant > 1 && parts[significant - 1] == 0) {
            significant--;
        }
        return Arrays.hashCode(Arrays.copyOf(parts, significant));
    }

    @Override
    public String toString() {
        return raw.replace('_', '.');
    }
}
