package io.github.navuk35.surrealdb.spring.sample;

public class LedgerEntry {

    private String kind;
    private long amount;

    public LedgerEntry() {
    }

    public LedgerEntry(String kind, long amount) {
        this.kind = kind;
        this.amount = amount;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }
}
