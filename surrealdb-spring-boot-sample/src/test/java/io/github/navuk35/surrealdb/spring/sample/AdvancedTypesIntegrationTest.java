package io.github.navuk35.surrealdb.spring.sample;

import com.surrealdb.RecordId;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage matrix MEDIUM items: bytes (#8), geometry (#9), nested arrays
 * of objects (#12), option fields (#13), FLEXIBLE object fields (#14) and
 * record ranges (#15).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdvancedTypesIntegrationTest extends AbstractSurrealIntegrationTest {

    public static class Item {
        public String sku;
        public List<String> tags;

        public Item() {
        }

        Item(String sku, List<String> tags) {
            this.sku = sku;
            this.tags = tags;
        }
    }

    public static class PurchaseOrder {
        public RecordId id;
        public String ref;
        public List<Item> items;

        public PurchaseOrder() {
        }
    }

    public static class OptionalUser {
        public RecordId id;
        public String name;
        public String nick;

        public OptionalUser() {
        }
    }

    public static class FlexibleUser {
        public RecordId id;
        public String name;
        public Map<String, Object> meta;

        public FlexibleUser() {
        }
    }

    @Autowired
    private SurrealTemplate template;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        template.query("""
                DEFINE TABLE IF NOT EXISTS purchase_order SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS blob_box SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS city SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS ranged_item SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS opt_user SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS name ON opt_user TYPE string;
                DEFINE FIELD IF NOT EXISTS nick ON opt_user TYPE option<string>;
                DEFINE TABLE IF NOT EXISTS flex_user SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS name ON flex_user TYPE string;
                DEFINE FIELD IF NOT EXISTS meta ON flex_user TYPE object FLEXIBLE;
                DELETE purchase_order;
                DELETE blob_box;
                DELETE city;
                DELETE ranged_item;
                DELETE opt_user;
                DELETE flex_user;
                """);
    }

    @Test
    void bytesRoundTripThroughQuery() {
        template.query("CREATE blob_box:b1 SET data = <bytes>'hello'");

        byte[] data = template.query("SELECT VALUE data FROM ONLY blob_box:b1")
                .list(0, value -> value.getBytes()).get(0);

        assertThat(new String(data)).isEqualTo("hello");
    }

    @Test
    void geometryPointReadsAsPoint2D() {
        template.query("CREATE city:london SET centre = (-0.118, 51.509)");

        java.awt.geom.Point2D.Double centre = template
                .query("SELECT VALUE centre FROM ONLY city:london")
                .list(0, value -> value.getGeometry().getPoint()).get(0);

        assertThat(centre.getX()).isEqualTo(-0.118);
        assertThat(centre.getY()).isEqualTo(51.509);
    }

    @Test
    void nestedArraysOfObjectsRoundTripOnBothPaths() {
        PurchaseOrder order = new PurchaseOrder();
        order.ref = "PO-1";
        order.items = List.of(
                new Item("sku-a", List.of("red", "small")),
                new Item("sku-b", List.of("blue")));

        PurchaseOrder direct = template.create(PurchaseOrder.class, "purchase_order", order).get(0);
        PurchaseOrder loadedDirect = template.select(PurchaseOrder.class, direct.id).orElseThrow();
        assertNestedOrderIntact(loadedDirect);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PurchaseOrder inTx = tx.execute(status ->
                template.create(PurchaseOrder.class, "purchase_order", order).get(0));
        PurchaseOrder loadedTx = template.select(PurchaseOrder.class, inTx.id).orElseThrow();
        assertNestedOrderIntact(loadedTx);
    }

    @Test
    void optionFieldIsNullWhenAbsentAndValueWhenSet() {
        template.query("CREATE opt_user:plain SET name = 'NoNick'");
        template.query("CREATE opt_user:nicked SET name = 'Nicked', nick = 'Nick'");

        OptionalUser plain = template.select(OptionalUser.class,
                new RecordId("opt_user", "plain")).orElseThrow();
        OptionalUser nicked = template.select(OptionalUser.class,
                new RecordId("opt_user", "nicked")).orElseThrow();

        assertThat(plain.nick).as("absent option<string> maps to null").isNull();
        assertThat(nicked.nick).isEqualTo("Nick");
    }

    @Test
    void flexibleObjectFieldRoundTripsAMap() {
        template.query("CREATE flex_user:f SET name = 'F', meta = { theme: 'dark', tries: 3 }");

        FlexibleUser user = template.select(FlexibleUser.class,
                new RecordId("flex_user", "f")).orElseThrow();

        assertThat(user.meta)
                .containsEntry("theme", "dark")
                .containsEntry("tries", 3L);
    }

    @Test
    void recordRangesSelectSubsetsById() {
        template.query("""
                CREATE ranged_item:1 SET n = 1;
                CREATE ranged_item:2 SET n = 2;
                CREATE ranged_item:3 SET n = 3;
                """);

        List<Long> subset = template.query("SELECT VALUE n FROM ranged_item:1..=2")
                .list(0, value -> value.getLong());

        assertThat(subset).containsExactly(1L, 2L);
    }

    private void assertNestedOrderIntact(PurchaseOrder loaded) {
        assertThat(loaded.ref).isEqualTo("PO-1");
        assertThat(loaded.items).hasSize(2);
        assertThat(loaded.items.get(0).sku).isEqualTo("sku-a");
        assertThat(loaded.items.get(0).tags).containsExactly("red", "small");
        assertThat(loaded.items.get(1).tags).containsExactly("blue");
    }
}
