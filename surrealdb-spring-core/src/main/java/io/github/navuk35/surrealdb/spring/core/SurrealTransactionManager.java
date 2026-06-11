package io.github.navuk35.surrealdb.spring.core;

import com.surrealdb.Surreal;
import com.surrealdb.Transaction;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class SurrealTransactionManager extends AbstractPlatformTransactionManager
        implements ResourceTransactionManager {

    private final Surreal surreal;

    public SurrealTransactionManager(Surreal surreal) {
        this.surreal = surreal;
    }

    @Override
    public Object getResourceFactory() {
        return surreal;
    }

    @Override
    protected Object doGetTransaction() {
        SurrealResourceHolder holder = (SurrealResourceHolder) TransactionSynchronizationManager
                .getResource(surreal);
        return new SurrealTransactionObject(holder);
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((SurrealTransactionObject) transaction).hasTransaction();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
            throw new InvalidIsolationLevelException(
                    "SurrealTransactionManager does not support custom isolation levels");
        }
        if (TransactionSynchronizationManager.hasResource(surreal)) {
            throw new CannotCreateTransactionException(
                    "Surreal connection already bound to thread — nested transactions not supported");
        }
        try {
            Transaction tx = surreal.beginTransaction();
            SurrealResourceHolder holder = new SurrealResourceHolder(tx);
            holder.setSynchronizedWithTransaction(true);
            if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
                holder.setTimeoutInSeconds(definition.getTimeout());
            }
            TransactionSynchronizationManager.bindResource(surreal, holder);
            ((SurrealTransactionObject) transaction).setResourceHolder(holder);
        }
        catch (Exception ex) {
            throw new CannotCreateTransactionException("Could not begin SurrealDB transaction", ex);
        }
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        SurrealResourceHolder holder = requiredResourceHolder(status, "commit");
        try {
            holder.getTransaction().commit();
        }
        catch (Exception ex) {
            throw new TransactionSystemException("Could not commit SurrealDB transaction", ex);
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        SurrealResourceHolder holder = requiredResourceHolder(status, "rollback");
        try {
            holder.getTransaction().cancel();
        }
        catch (Exception ex) {
            throw new TransactionSystemException("Could not roll back SurrealDB transaction", ex);
        }
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        requiredResourceHolder(status, "set rollback-only").setRollbackOnly();
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        TransactionSynchronizationManager.unbindResource(surreal);
        SurrealTransactionObject txObject = (SurrealTransactionObject) transaction;
        SurrealResourceHolder holder = txObject.getResourceHolder();
        if (holder != null) {
            holder.clear();
        }
        txObject.setResourceHolder(null);
    }

    private SurrealResourceHolder requiredResourceHolder(DefaultTransactionStatus status, String operation) {
        SurrealResourceHolder holder = ((SurrealTransactionObject) status.getTransaction())
                .getResourceHolder();
        if (holder == null) {
            throw new TransactionSystemException(
                    "No SurrealDB transaction available on " + operation);
        }
        return holder;
    }
}
