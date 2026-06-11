package io.github.navuk35.surrealdb.spring.sample;

import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final SurrealTemplate template;
    private final LedgerAuditService auditService;

    public LedgerService(SurrealTemplate template, LedgerAuditService auditService) {
        this.template = template;
        this.auditService = auditService;
    }

    @Transactional
    public void createEntries() {
        template.query("CREATE ledger_entry SET kind = 'debit', amount = 100");
        template.query("CREATE ledger_entry SET kind = 'credit', amount = 100");
    }

    @Transactional
    public void createWithCrudThenFail() {
        template.create(LedgerEntry.class, "ledger_entry", new LedgerEntry("debit", 100));
        throw new IllegalStateException("business failure after create");
    }

    @Transactional
    public void createOuterAndSwallowInnerFailure() {
        template.query("CREATE ledger_entry SET kind = 'outer', amount = 1");
        try {
            auditService.recordAuditThenFail();
        }
        catch (RuntimeException ex) {
            // swallowed on purpose — the inner participating transaction must still
            // have marked the shared transaction rollback-only
        }
    }
}
