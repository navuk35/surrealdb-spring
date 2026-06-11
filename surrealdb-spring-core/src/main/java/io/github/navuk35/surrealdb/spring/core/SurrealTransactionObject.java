package io.github.navuk35.surrealdb.spring.core;

import org.springframework.transaction.support.SmartTransactionObject;

class SurrealTransactionObject implements SmartTransactionObject {

    private SurrealResourceHolder resourceHolder;

    SurrealTransactionObject(SurrealResourceHolder resourceHolder) {
        this.resourceHolder = resourceHolder;
    }

    boolean hasTransaction() {
        return resourceHolder != null && resourceHolder.getTransaction() != null;
    }

    SurrealResourceHolder getResourceHolder() {
        return resourceHolder;
    }

    void setResourceHolder(SurrealResourceHolder resourceHolder) {
        this.resourceHolder = resourceHolder;
    }

    @Override
    public boolean isRollbackOnly() {
        return resourceHolder != null && resourceHolder.isRollbackOnly();
    }

    @Override
    public void flush() {
        // SurrealDB has no flushable session state
    }
}
