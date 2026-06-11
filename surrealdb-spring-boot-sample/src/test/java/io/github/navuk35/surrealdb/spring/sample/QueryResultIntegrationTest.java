package io.github.navuk35.surrealdb.spring.sample;

import io.github.navuk35.surrealdb.spring.core.SurrealQueryResult;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QueryResultIntegrationTest extends AbstractSurrealIntegrationTest {

    record Customer(String name, String email) {
    }

    @Autowired
    private SurrealTemplate template;

    @BeforeEach
    void cleanCustomers() {
        template.query("""
                DEFINE TABLE IF NOT EXISTS customer SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS name ON customer TYPE string;
                DEFINE FIELD IF NOT EXISTS email ON customer TYPE string;
                DEFINE INDEX IF NOT EXISTS customer_email ON customer FIELDS email UNIQUE;
                DELETE customer;
                """);
    }

    @Test
    void statementErrorsThrowEagerlyWithoutConsumingTheResult() {
        template.query("CREATE customer SET name = 'Navin', email = 'navin@example.com'");

        // the bug we found while testing Surge: the driver only raises this
        // when take() is called — the template must surface it immediately
        assertThatThrownBy(() -> template
                .query("CREATE customer SET name = 'Dup', email = 'navin@example.com'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listMapsRowsOntoRecords() {
        template.query("CREATE customer SET name = 'Asha', email = 'asha@example.com'");
        template.query("CREATE customer SET name = 'Ravi', email = 'ravi@example.com'");

        List<Customer> customers = template
                .query("SELECT name, email FROM customer ORDER BY name")
                .list(0, Customer.class);

        assertThat(customers).containsExactly(
                new Customer("Asha", "asha@example.com"),
                new Customer("Ravi", "ravi@example.com"));
    }

    @Test
    void valueMapperHandlesShapesThatFitNoEntity() {
        template.query("CREATE customer SET name = 'Asha', email = 'asha@example.com'");
        template.query("CREATE customer SET name = 'Ravi', email = 'ravi@example.com'");

        List<Long> counts = template
                .query("SELECT count() FROM customer GROUP ALL")
                .list(0, value -> value.getObject().get("count").getLong());

        assertThat(counts).containsExactly(2L);
    }

    @Test
    void scalarReadsReturnStatements() {
        SurrealQueryResult result = template.query("RETURN 6 * 7");

        assertThat(result.scalar(0, Long.class)).isEqualTo(42L);
    }

    @Test
    void firstIsEmptyWhenNothingMatches() {
        Optional<Customer> none = template
                .query("SELECT name, email FROM customer WHERE name = 'Nobody'")
                .first(0, Customer.class);

        assertThat(none).isEmpty();
    }

    @Test
    void multiStatementResultsAreAddressableByIndex() {
        SurrealQueryResult result = template.query("""
                RETURN 'first';
                RETURN 'second';
                """);

        assertThat(result.size()).isEqualTo(2);
        assertThat(result.scalar(1, String.class)).isEqualTo("second");
    }

    @Test
    void parameterizedQueriesAreCheckedEagerlyToo() {
        template.query("CREATE customer SET name = $name, email = $email",
                Map.of("name", "Asha", "email", "asha@example.com"));

        assertThatThrownBy(() -> template.query(
                "CREATE customer SET name = $name, email = $email",
                Map.of("name", "Copy", "email", "asha@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void queryForObjectSignalsEmptyResultExplicitly() {
        assertThatThrownBy(() -> template.queryForObject(
                "SELECT name, email FROM customer WHERE name = 'Nobody'",
                Customer.class, 0))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }
}
