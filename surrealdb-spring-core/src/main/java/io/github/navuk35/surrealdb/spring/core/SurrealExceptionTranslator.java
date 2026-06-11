package io.github.navuk35.surrealdb.spring.core;

import com.surrealdb.AlreadyExistsException;
import com.surrealdb.NotFoundException;
import com.surrealdb.QueryException;
import com.surrealdb.ServerException;
import com.surrealdb.SurrealException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

public class SurrealExceptionTranslator {

    public DataAccessException translate(String task, String resource, Throwable ex) {
        if (ex instanceof NotFoundException notFound) {
            return new EmptyResultDataAccessException(notFound.getMessage(), 1, notFound);
        }
        if (ex instanceof AlreadyExistsException alreadyExists) {
            return new DataIntegrityViolationException(alreadyExists.getMessage(), alreadyExists);
        }
        if (ex instanceof QueryException query) {
            return new InvalidDataAccessResourceUsageException(query.getMessage(), query);
        }
        if (ex instanceof SurrealException surreal) {
            // unique index violations arrive as a generic ServerException,
            // recognizable only by the server's message
            if (isDuplicateKey(surreal.getMessage())) {
                return new DuplicateKeyException(surreal.getMessage(), surreal);
            }
            if (surreal instanceof ServerException server) {
                return new DataAccessResourceFailureException(server.getMessage(), server);
            }
            return new DataAccessResourceFailureException(surreal.getMessage(), surreal);
        }
        return new DataAccessResourceFailureException(task, ex);
    }

    private static boolean isDuplicateKey(String message) {
        return message != null
                && message.contains("index")
                && message.contains("already contains");
    }
}
