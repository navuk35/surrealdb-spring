package io.github.navuk35.surrealdb.spring.sample;

import com.surrealdb.RecordId;
import com.surrealdb.Relation;
import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Record links, FETCH resolution, graph edges (RELATE) and SCHEMAFULL
 * enforcement — coverage matrix items #5, #6 and #7.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RelationshipIntegrationTest extends AbstractSurrealIntegrationTest {

    public static class Post {
        public RecordId id;
        public String title;

        public Post() {
        }
    }

    /** Shape WITHOUT FetCH: links stay record ids. */
    public static class AuthorWithLinks {
        public RecordId id;
        public String name;
        public List<RecordId> posts;

        public AuthorWithLinks() {
        }
    }

    /** Shape WITH FETCH: links resolve to full nested objects. */
    public static class AuthorWithPosts {
        public RecordId id;
        public String name;
        public List<Post> posts;

        public AuthorWithPosts() {
        }
    }

    public static class Wrote extends Relation {
        public String place;

        public Wrote() {
        }
    }

    @Autowired
    private SurrealTemplate template;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTables() {
        template.query("""
                DEFINE TABLE IF NOT EXISTS author SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS post SCHEMALESS;
                DEFINE TABLE IF NOT EXISTS wrote TYPE RELATION;
                DELETE author;
                DELETE post;
                DELETE wrote;
                """);
    }

    @Test
    void recordLinksStayIdsWithoutFetchAndResolveWithFetch() {
        template.query("""
                CREATE post:one SET title = 'First';
                CREATE post:two SET title = 'Second';
                CREATE author:navin SET name = 'Navin', posts = [post:one, post:two];
                """);

        AuthorWithLinks linked = template.query("SELECT * FROM author")
                .list(0, AuthorWithLinks.class).get(0);
        assertThat(linked.posts)
                .as("without FETCH, links are record ids")
                .hasSize(2)
                .allMatch(rid -> rid.getTable().equals("post"));

        AuthorWithPosts fetched = template.query("SELECT * FROM author FETCH posts")
                .list(0, AuthorWithPosts.class).get(0);
        assertThat(fetched.posts)
                .as("with FETCH, links resolve to nested objects")
                .extracting(post -> post.title)
                .containsExactly("First", "Second");
    }

    @Test
    void relateCreatesTypedEdgeWithProperties() {
        template.query("CREATE author:a SET name = 'A'");
        template.query("CREATE post:p SET title = 'T'");
        RecordId author = new RecordId("author", "a");
        RecordId post = new RecordId("post", "p");

        Wrote content = new Wrote();
        content.place = "Bangalore";
        Wrote edge = template.relate(Wrote.class, author, "wrote", post, content);

        assertThat(edge.place).isEqualTo("Bangalore");

        List<String> titles = template
                .query("SELECT VALUE ->wrote->post.title FROM ONLY author:a")
                .list(0, v -> v.getString());
        assertThat(titles).containsExactly("T");
    }

    @Test
    void relateInsideTransactionMapsTheEdgeAndCommits() {
        template.query("CREATE author:tx SET name = 'TX'");
        template.query("CREATE post:tx SET title = 'TxPost'");
        RecordId author = new RecordId("author", "tx");
        RecordId post = new RecordId("post", "tx");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Wrote content = new Wrote();
        content.place = "Remote";
        Wrote edge = tx.execute(status ->
                template.relate(Wrote.class, author, "wrote", post, content));

        assertThat(edge.place).isEqualTo("Remote");
        long edges = template.query("SELECT count() FROM wrote GROUP ALL")
                .list(0, v -> v.getObject().get("count").getLong())
                .stream().findFirst().orElse(0L);
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void relateWithoutContentWorksOnBothPaths() {
        template.query("""
                CREATE author:nc1 SET name = 'NC1';
                CREATE author:nc2 SET name = 'NC2';
                CREATE post:nc1 SET title = 'P1';
                CREATE post:nc2 SET title = 'P2';
                """);

        Wrote direct = template.relate(Wrote.class,
                new RecordId("author", "nc1"), "wrote", new RecordId("post", "nc1"), null);
        assertThat(direct).as("content-less relate on the driver path").isNotNull();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Wrote inTx = tx.execute(status -> template.relate(Wrote.class,
                new RecordId("author", "nc2"), "wrote", new RecordId("post", "nc2"), null));
        assertThat(inTx).as("content-less relate inside a transaction").isNotNull();
    }

    @Test
    void schemafullRejectsUndeclaredFieldsOnBothPaths() {
        template.query("""
                DEFINE TABLE IF NOT EXISTS strict_user SCHEMAFULL;
                DEFINE FIELD IF NOT EXISTS firstName ON strict_user TYPE string;
                """);

        // direct path
        assertThatThrownBy(() -> template.query(
                "CREATE strict_user CONTENT { firstName: 'a', extra: 1 }"))
                .as("SurrealDB 3.x errors on undeclared SCHEMAFULL fields")
                .isInstanceOf(DataAccessException.class);

        // transactional translation path (CREATE type::table($tb) CONTENT $content)
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                template.create(Greeting.class, "strict_user", new Greeting("a", "extra"))))
                .isInstanceOf(DataAccessException.class);
    }
}
