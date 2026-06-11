package io.github.navuk35.surrealdb.spring.surge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationVersionTest {

    @Test
    void comparesNumericallyNotLexicographically() {
        // a string sort would put 0_10 before 0_2
        assertThat(MigrationVersion.parse("0_2"))
                .isLessThan(MigrationVersion.parse("0_10"));
    }

    @Test
    void comparesMultiPartVersions() {
        assertThat(MigrationVersion.parse("1_2_3")).isGreaterThan(MigrationVersion.parse("1_2"));
        assertThat(MigrationVersion.parse("2")).isGreaterThan(MigrationVersion.parse("1_9_9"));
    }

    @Test
    void treatsTrailingZeroAsSameVersion() {
        assertThat(MigrationVersion.parse("1").compareTo(MigrationVersion.parse("1_0"))).isZero();
    }

    @Test
    void displaysWithDots() {
        assertThat(MigrationVersion.parse("0_1_2").toString()).isEqualTo("0.1.2");
    }

    @Test
    void rejectsNonNumericVersions() {
        assertThatThrownBy(() -> MigrationVersion.parse("abc"))
                .isInstanceOf(SurgeException.class);
        assertThatThrownBy(() -> MigrationVersion.parse(""))
                .isInstanceOf(SurgeException.class);
    }
}
