package io.github.navuk35.surrealdb.spring.surge;

public record SurgeResult(int versionedApplied, int repeatableApplied) {

    public int totalApplied() {
        return versionedApplied + repeatableApplied;
    }
}
