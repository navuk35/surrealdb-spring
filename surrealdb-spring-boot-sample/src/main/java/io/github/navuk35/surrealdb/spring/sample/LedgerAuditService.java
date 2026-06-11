package io.github.navuk35.surrealdb.spring.sample;

import io.github.navuk35.surrealdb.spring.core.SurrealTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerAuditService {

    private final SurrealTemplate template;

    public LedgerAuditService(SurrealTemplate template) {
        this.template = template;
    }

    @Transactional
    public void recordAuditThenFail() {
        template.query("CREATE ledger_entry SET kind = 'audit', amount = 1");
        throw new IllegalStateException("audit failure");
    }
}
