package io.github.navuk35.surrealdb.spring.core;

import com.surrealdb.Transaction;
import org.springframework.transaction.support.ResourceHolderSupport;

public class SurrealResourceHolder extends ResourceHolderSupport {

    private final Transaction transaction;

    public SurrealResourceHolder(Transaction transaction) {
        this.transaction = transaction;
    }

    public Transaction getTransaction() {
        return transaction;
    }
}
