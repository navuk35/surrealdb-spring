package io.github.navuk35.surrealdb.spring.surge;

public class SurgeException extends RuntimeException {

    public SurgeException(String message) {
        super(message);
    }

    public SurgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
