package io.github.navuk35.surrealdb.spring.sample;

import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TransactionalIntegrationTest extends AbstractSurrealIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private SurrealTemplate template;

    @BeforeEach
    void cleanLedger() {
        // Pre-define the table: a rolled-back transaction discards even the
        // implicit table definition, which would make counting rows throw.
        template.query("DEFINE TABLE IF NOT EXISTS ledger_entry SCHEMALESS");
        template.query("DELETE ledger_entry");
    }

    @Test
    void transactionalCreatePersistsBothEntries() {
        ledgerService.createEntries();

        assertThat(countLedgerEntries()).isEqualTo(2);
    }

    @Test
    void rollbackDiscardsTemplateCrudOperations() {
        assertThatThrownBy(() -> ledgerService.createWithCrudThenFail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("business failure after create");

        assertThat(countLedgerEntries())
                .as("entry created via template.create() inside @Transactional must roll back")
                .isZero();
    }

    @Test
    void innerParticipatingFailureForcesRollbackOfOuterTransaction() {
        assertThatThrownBy(() -> ledgerService.createOuterAndSwallowInnerFailure())
                .as("outer commit must fail because the inner transaction marked rollback-only")
                .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(countLedgerEntries())
                .as("neither outer nor inner entries may survive the rollback")
                .isZero();
    }

    private long countLedgerEntries() {
        return template.query("SELECT count() FROM ledger_entry GROUP ALL")
                .list(0, value -> value.getObject().get("count").getLong())
                .stream().findFirst().orElse(0L);
    }
}
