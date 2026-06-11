package io.github.navuk35.surrealdb.spring.sample;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class Invoice {

    private String number;
    private Instant issuedAt;
    private BigDecimal total;
    private List<String> lines;

    public Invoice() {
    }

    public Invoice(String number, Instant issuedAt, BigDecimal total, List<String> lines) {
        this.number = number;
        this.issuedAt = issuedAt;
        this.total = total;
        this.lines = lines;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public List<String> getLines() {
        return lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }
}
