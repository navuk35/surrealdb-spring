package io.github.navuk35.surrealdb.spring.sample;

import com.surrealdb.RecordId;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips every SurrealDB data type through the template — both the
 * direct driver path and the transactional SurrealQL-translation path —
 * per the documentation coverage matrix (datamodel: strings, numbers,
 * decimals, datetimes, durations, uuids, arrays, NONE vs NULL, record ids).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DataTypeMappingIntegrationTest extends AbstractSurrealIntegrationTest {

    public static class Reading {
        public RecordId id;
        public String label;
        public long count;
        public double ratio;
        public BigDecimal price;
        public boolean active;
        public ZonedDateTime at;
        public Duration took;
        public UUID token;
        public List<String> tags;

        public Reading() {
        }

        static Reading sample() {
            Reading reading = new Reading();
            reading.label = "sensor-1";
            reading.count = 42;
            reading.ratio = 0.75;
            reading.price = new BigDecimal("1999.99");
            reading.active = true;
            reading.at = ZonedDateTime.parse("2026-06-11T10:15:30.123456789Z");
            reading.took = Duration.ofHours(52);
            reading.token = UUID.fromString("89bab8b8-0001-0002-0003-7c0d2bfe571d");
            reading.tags = List.of("alpha", "beta");
            return reading;
        }
    }

    @Autowired
    private SurrealTemplate template;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        template.query("""
                DEFINE TABLE IF NOT EXISTS reading SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS ridform SCHEMALESS;
                DELETE reading;
                DELETE ridform;
                """);
    }

    @Test
    void allTypesRoundTripThroughDriverPath() {
        Reading created = template.create(Reading.class, "reading", Reading.sample()).get(0);
        Reading loaded = template.select(Reading.class, created.id).orElseThrow();

        assertReadingIntact(loaded);
    }

    @Test
    void allTypesRoundTripThroughTransactionalTranslationPath() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Reading created = tx.execute(status ->
                template.create(Reading.class, "reading", Reading.sample()).get(0));
        Reading loaded = template.select(Reading.class, created.id).orElseThrow();

        assertReadingIntact(loaded);
    }

    @Test
    void decimalKeepsExactPrecision() {
        BigDecimal exact = template.query("RETURN 99.99dec").scalar(0, BigDecimal.class);

        assertThat(exact).isEqualByComparingTo(new BigDecimal("99.99"));
    }

    @Test
    void datetimeKeepsNanosecondPrecision() {
        template.query("CREATE reading:nanos SET at = d'2026-01-02T03:04:05.123456789Z'");

        ZonedDateTime at = template.query("SELECT VALUE at FROM ONLY reading:nanos")
                .scalar(0, ZonedDateTime.class);

        assertThat(at.getNano()).isEqualTo(123_456_789);
    }

    @Test
    void noneRemovesAFieldWhileNullKeepsIt() {
        template.query("CREATE reading:nn SET label = 'x', a = NULL, b = NONE");

        Map<String, Boolean> presence = template.query("SELECT * FROM ONLY reading:nn")
                .list(0, row -> {
                    com.surrealdb.Object entry = row.getObject();
                    return Map.of(
                            "aPresent", entry.get("a") != null && !entry.get("a").isNone(),
                            "aIsNull", entry.get("a") != null && entry.get("a").isNull(),
                            "bPresent", entry.get("b") != null && !entry.get("b").isNone());
                }).get(0);

        assertThat(presence.get("aPresent")).as("NULL field is present").isTrue();
        assertThat(presence.get("aIsNull")).as("NULL field reads as null").isTrue();
        assertThat(presence.get("bPresent")).as("NONE field does not exist").isFalse();
    }

    @Test
    void numericRecordIdsBindCorrectlyInTransactions() {
        template.query("CREATE ridform:10 SET label = 'numeric'");
        RecordId numericId = new RecordId("ridform", 10);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Optional<Reading> inTx = template.select(Reading.class, numericId);
            assertThat(inTx).as("numeric record id must bind as $rid, not a string").isPresent();
            template.delete(numericId);
        });

        assertThat(template.select(Reading.class, numericId))
                .as("delete bound by numeric record id must have removed ridform:10")
                .isEmpty();
    }

    @Test
    void stringAndNumericIdsAreDistinctRecords() {
        template.query("CREATE ridform:20 SET label = 'numeric'");
        template.query("CREATE ridform:`20s` SET label = 'string'");

        Optional<Reading> numeric = template.select(Reading.class, new RecordId("ridform", 20));
        Optional<Reading> text = template.select(Reading.class, new RecordId("ridform", "20s"));

        assertThat(numeric.orElseThrow().label).isEqualTo("numeric");
        assertThat(text.orElseThrow().label).isEqualTo("string");
    }

    private void assertReadingIntact(Reading loaded) {
        Reading expected = Reading.sample();
        assertThat(loaded.label).isEqualTo(expected.label);
        assertThat(loaded.count).isEqualTo(expected.count);
        assertThat(loaded.ratio).isEqualTo(expected.ratio);
        assertThat(loaded.price).isEqualByComparingTo(expected.price);
        assertThat(loaded.active).isTrue();
        assertThat(loaded.at.toInstant()).isEqualTo(expected.at.toInstant());
        assertThat(loaded.took).isEqualTo(expected.took);
        assertThat(loaded.token).isEqualTo(expected.token);
        assertThat(loaded.tags).containsExactly("alpha", "beta");
    }
}
