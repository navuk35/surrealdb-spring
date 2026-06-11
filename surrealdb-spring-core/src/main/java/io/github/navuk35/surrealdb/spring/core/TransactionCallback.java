package io.github.navuk35.surrealdb.spring.core;

@FunctionalInterface
public interface TransactionCallback<T> {

    T doInTransaction(SurrealTemplate template) throws Exception;
}
