package io.github.navuk35.surrealdb.spring.core;

import com.surrealdb.Value;

/**
 * Optional escape hatch for result shapes that don't map naturally onto a
 * POJO or record — aggregations, mixed projections, partial extraction.
 * For everything else, prefer the {@code Class}-token accessors: the driver
 * maps structured results (nested objects, records, generic collections)
 * without any mapper.
 */
@FunctionalInterface
public interface ValueMapper<T> {

    T map(Value value);
}
