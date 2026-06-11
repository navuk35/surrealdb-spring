package io.github.navuk35.surrealdb.spring.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CacheComplexValuesIntegrationTest extends AbstractSurrealIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;

    @Test
    void cachesDtoWithJavaTimeAndBigDecimalFields() {
        Invoice first = invoiceService.findInvoice("INV-1");
        Invoice cached = invoiceService.findInvoice("INV-1");

        assertThat(invoiceService.invoiceCallCount()).isEqualTo(1);
        assertThat(cached.getNumber()).isEqualTo("INV-1");
        assertThat(cached.getIssuedAt()).isEqualTo(first.getIssuedAt());
        assertThat(cached.getTotal()).isEqualByComparingTo(new BigDecimal("1999.99"));
    }

    @Test
    void cachesListsOfDtos() {
        List<Invoice> first = invoiceService.findAllInvoices("acme");
        List<Invoice> cached = invoiceService.findAllInvoices("acme");

        assertThat(invoiceService.listCallCount()).isEqualTo(1);
        assertThat(cached).hasSize(2);
        assertThat(cached.get(0)).isInstanceOf(Invoice.class);
        assertThat(cached.get(1).getLines()).containsExactly("keyboard", "mouse");
    }

    @Test
    void cachesDeeplyNestedStructures() {
        Invoice first = invoiceService.findInvoiceWithLines("INV-9");
        Invoice cached = invoiceService.findInvoiceWithLines("INV-9");

        assertThat(invoiceService.nestedCallCount()).isEqualTo(1);
        assertThat(cached.getLines()).containsExactly("line-A", "line-B", "line-C");
        assertThat(cached.getTotal()).isEqualByComparingTo(first.getTotal());
    }
}
