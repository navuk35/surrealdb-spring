package io.github.navuk35.surrealdb.spring.sample;

import com.surrealdb.RecordId;
import com.surrealdb.UpType;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises every SurrealTemplate CRUD operation INSIDE a transaction —
 * these run through the SurrealQL translation layer (the driver's
 * Transaction only exposes query()), which is a completely different code
 * path from the non-transactional driver calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionalCrudIntegrationTest extends AbstractSurrealIntegrationTest {

    public static class Product {
        public RecordId id;
        public String name;
        public long stock;

        public Product() {
        }

        public Product(String name, long stock) {
            this.name = name;
            this.stock = stock;
        }
    }

    @Autowired
    private SurrealTemplate template;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // tables must exist before DELETE — with eager error checking, a
        // DELETE on an undefined table is a statement error, not a no-op
        template.query("""
                DEFINE TABLE IF NOT EXISTS product SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS stocked_in TYPE RELATION;
                DELETE product;
                DELETE stocked_in;
                """);
    }

    @Test
    void createInsideTransactionIsReadableBeforeCommitAndPersists() {
        List<Product> created = tx.execute(status -> {
            List<Product> result = template.create(Product.class, "product", new Product("Laptop", 5));

            // read-your-writes within the same transaction
            Optional<Product> inTx = template.select(Product.class, result.get(0).id);
            assertThat(inTx).isPresent();
            assertThat(inTx.get().name).isEqualTo("Laptop");
            return result;
        });

        Optional<Product> committed = template.select(Product.class, created.get(0).id);
        assertThat(committed).isPresent();
        assertThat(committed.get().stock).isEqualTo(5);
    }

    @Test
    void updateContentInsideTransactionRollsBackCompletely() {
        Product laptop = template.create(Product.class, "product", new Product("Laptop", 5)).get(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Product replacement = new Product("Laptop Pro", 9);
            Product updated = template.update(Product.class, laptop.id, UpType.CONTENT, replacement);
            assertThat(updated.name).isEqualTo("Laptop Pro");
            throw new IllegalStateException("rollback please");
        })).isInstanceOf(IllegalStateException.class);

        Product after = template.select(Product.class, laptop.id).orElseThrow();
        assertThat(after.name).isEqualTo("Laptop");
        assertThat(after.stock).isEqualTo(5);
    }

    @Test
    void updateMergeInsideTransactionPersistsOnCommit() {
        Product laptop = template.create(Product.class, "product", new Product("Laptop", 5)).get(0);

        tx.executeWithoutResult(status -> {
            Product stockOnly = new Product(null, 42);
            template.update(Product.class, laptop.id, UpType.MERGE, stockOnly);
        });

        Product after = template.select(Product.class, laptop.id).orElseThrow();
        assertThat(after.stock).isEqualTo(42);
        assertThat(after.name).as("MERGE must keep fields absent from the patch").isEqualTo("Laptop");
    }

    @Test
    void deleteByRecordIdInsideTransactionRollsBack() {
        Product laptop = template.create(Product.class, "product", new Product("Laptop", 5)).get(0);

        tx.executeWithoutResult(status -> {
            template.delete(laptop.id);
            assertThat(template.select(Product.class, laptop.id))
                    .as("delete must be visible inside its own transaction")
                    .isEmpty();
            status.setRollbackOnly();
        });

        assertThat(template.select(Product.class, laptop.id))
                .as("rolled-back delete must restore the record")
                .isPresent();
    }

    @Test
    void deleteWholeTableInsideTransactionPersistsOnCommit() {
        template.create(Product.class, "product", new Product("A", 1));
        template.create(Product.class, "product", new Product("B", 2));

        tx.executeWithoutResult(status -> template.delete("product"));

        Iterator<Product> remaining = template.select(Product.class, "product");
        assertThat(remaining.hasNext()).isFalse();
    }

    @Test
    void selectIteratorInsideTransactionSeesUncommittedRows() {
        tx.executeWithoutResult(status -> {
            template.create(Product.class, "product", new Product("A", 1));
            template.create(Product.class, "product", new Product("B", 2));

            Iterator<Product> inTx = template.select(Product.class, "product");
            int count = 0;
            while (inTx.hasNext()) {
                inTx.next();
                count++;
            }
            assertThat(count).isEqualTo(2);
        });
    }

    @Test
    void relateInsideTransactionRollsBackTheEdge() {
        Product laptop = template.create(Product.class, "product", new Product("Laptop", 5)).get(0);
        Product mouse = template.create(Product.class, "product", new Product("Mouse", 50)).get(0);

        tx.executeWithoutResult(status -> {
            template.query("RELATE $from->stocked_in->$to",
                    java.util.Map.of("from", laptop.id, "to", mouse.id));
            status.setRollbackOnly();
        });

        long edges = template.query("SELECT count() FROM stocked_in GROUP ALL")
                .list(0, v -> v.getObject().get("count").getLong())
                .stream().findFirst().orElse(0L);
        assertThat(edges).as("rolled-back RELATE must leave no edge").isZero();
    }

    @Test
    void relateRejectsNonIdentifierEdgeTableInsideTransaction() {
        Product laptop = template.create(Product.class, "product", new Product("Laptop", 5)).get(0);
        Product mouse = template.create(Product.class, "product", new Product("Mouse", 50)).get(0);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                template.relate(com.surrealdb.Relation.class, laptop.id,
                        "evil; REMOVE TABLE product", mouse.id, null)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }
}
