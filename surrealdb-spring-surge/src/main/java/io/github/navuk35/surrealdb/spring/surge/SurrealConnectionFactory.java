package io.github.navuk35.surrealdb.spring.surge;

import com.surrealdb.Surreal;

/**
 * Opens a connection scoped to one namespace/database. Tenant migrations
 * must NEVER switch namespace or database on a shared connection —
 * {@code useNs()/useDb()} mutate connection state and would redirect live
 * application traffic to the wrong tenant. Each tenant migration therefore
 * runs on its own short-lived connection obtained from this factory.
 */
@FunctionalInterface
public interface SurrealConnectionFactory {

    Surreal connect(String namespace, String database);
}
