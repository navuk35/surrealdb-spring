package io.github.navuk35.surrealdb.spring.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CacheObjectMapperTest {

    public static class Sample {
        public String name;
        public Instant at;
        public BigDecimal price;

        public Sample() {
        }

        Sample(String name, Instant at, BigDecimal price) {
            this.name = name;
            this.at = at;
            this.price = price;
        }
    }

    private final ObjectMapper mapper = CacheObjectMapper.create(new ObjectMapper());

    private Object roundTrip(Object value) throws Exception {
        String json = mapper.writeValueAsString(value);
        return mapper.readValue(json, Object.class);
    }

    @Test
    void pojoWithJavaTimeAndBigDecimalRoundTrips() throws Exception {
        Object back = roundTrip(new Sample("a", Instant.parse("2026-06-11T10:00:00Z"),
                new BigDecimal("19.99")));

        assertThat(back).isInstanceOf(Sample.class);
        Sample sample = (Sample) back;
        assertThat(sample.at).isEqualTo(Instant.parse("2026-06-11T10:00:00Z"));
        assertThat(sample.price).isEqualByComparingTo(new BigDecimal("19.99"));
    }

    @Test
    void immutableListOfPojosRoundTrips() throws Exception {
        Object back = roundTrip(List.of(new Sample("a", Instant.now(), BigDecimal.ONE),
                new Sample("b", Instant.now(), BigDecimal.TEN)));

        assertThat(back).isInstanceOf(List.class);
        assertThat((List<?>) back).hasSize(2).allMatch(Sample.class::isInstance);
    }

    @Test
    void immutableSetAndMapRoundTrip() throws Exception {
        Object backSet = roundTrip(Set.of("x"));
        Object backMap = roundTrip(Map.of("k", new Sample("a", Instant.now(), BigDecimal.ONE)));

        assertThat(backSet).isInstanceOf(Set.class);
        assertThat(backMap).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) backMap).get("k")).isInstanceOf(Sample.class);
    }

    @Test
    void plainStringsAndNumbersRoundTrip() throws Exception {
        assertThat(roundTrip("hello")).isEqualTo("hello");
        assertThat(roundTrip(42)).isEqualTo(42);
    }
}
