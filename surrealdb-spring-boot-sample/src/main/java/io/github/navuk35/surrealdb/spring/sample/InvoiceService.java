package io.github.navuk35.surrealdb.spring.sample;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class InvoiceService {

    private final AtomicInteger invoiceCalls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicInteger nestedCalls = new AtomicInteger();

    @Cacheable("invoices")
    public Invoice findInvoice(String number) {
        invoiceCalls.incrementAndGet();
        return new Invoice(number, Instant.parse("2026-06-11T10:00:00Z"),
                new BigDecimal("1999.99"), List.of());
    }

    @Cacheable("invoice-lists")
    public List<Invoice> findAllInvoices(String customer) {
        listCalls.incrementAndGet();
        return List.of(
                new Invoice("INV-1", Instant.parse("2026-06-01T08:30:00Z"),
                        new BigDecimal("100.50"), List.of("laptop")),
                new Invoice("INV-2", Instant.parse("2026-06-02T09:45:00Z"),
                        new BigDecimal("59.90"), List.of("keyboard", "mouse")));
    }

    @Cacheable("nested-invoices")
    public Invoice findInvoiceWithLines(String number) {
        nestedCalls.incrementAndGet();
        return new Invoice(number, Instant.parse("2026-06-11T11:11:11Z"),
                new BigDecimal("123.45"), List.of("line-A", "line-B", "line-C"));
    }

    int invoiceCallCount() {
        return invoiceCalls.get();
    }

    int listCallCount() {
        return listCalls.get();
    }

    int nestedCallCount() {
        return nestedCalls.get();
    }
}
