package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanOptionsTest {

    @Test
    void all_hasNoProjectionFilterOrLimit() {
        // Given / When
        ScanOptions sut = ScanOptions.all();

        // Then
        assertThat(sut.columns()).isEmpty();
        assertThat(sut.hasProjection()).isFalse();
        assertThat(sut.filter()).isEmpty();
        assertThat(sut.limit()).isEmpty();
    }

    @Test
    void withLimit_setsLimit() {
        // Given / When
        ScanOptions sut = ScanOptions.all().withLimit(ScanLimit.of(10));

        // Then
        assertThat(sut.limit()).contains(ScanLimit.of(10));
        assertThat(sut.columns()).isEmpty();
        assertThat(sut.filter()).isEmpty();
    }

    @Test
    void withColumns_setsProjection() {
        // Given / When
        ScanOptions sut = ScanOptions.all().withColumns(ColumnName.of("price"), ColumnName.of("qty"));

        // Then
        assertThat(sut.columns()).containsExactly(ColumnName.of("price"), ColumnName.of("qty"));
        assertThat(sut.hasProjection()).isTrue();
        assertThat(sut.limit()).isEmpty();
    }

    @Test
    void withFilter_setsFilter() {
        // Given
        ScanFilter filter = ScanFilter.gte(ColumnName.of("price"), 100);

        // When
        ScanOptions sut = ScanOptions.all().withFilter(filter);

        // Then
        assertThat(sut.filter()).contains(filter);
    }

    @Test
    void fluent_chainingPreservesAllFields() {
        // Given / When
        ScanFilter filter = ScanFilter.gte(ColumnName.of("price"), 50);
        ScanOptions sut = ScanOptions.all()
                                  .withColumns(ColumnName.of("price"), ColumnName.of("qty"))
                                  .withLimit(ScanLimit.of(10))
                                  .withFilter(filter);

        // Then
        assertThat(sut.columns()).containsExactly(ColumnName.of("price"), ColumnName.of("qty"));
        assertThat(sut.limit()).contains(ScanLimit.of(10));
        assertThat(sut.filter()).contains(filter);
    }

    @Test
    void withMethods_doNotMutateOriginal() {
        // Given
        ScanOptions original = ScanOptions.all();

        // When
        original.withLimit(ScanLimit.of(5)).withColumns(ColumnName.of("x"));

        // Then
        assertThat(original.limit()).isEmpty();
        assertThat(original.columns()).isEmpty();
    }

    @Test
    void withFilterAndWithLimit_rejectNull() {
        // Given: an absent filter or limit is the default, never a null argument
        ScanOptions sut = ScanOptions.all();

        // When / Then
        assertThatThrownBy(() -> sut.withFilter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sut.withLimit(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void scanLimit_acceptsZeroAndMore(long rows) {
        // When
        ScanLimit result = ScanLimit.of(rows);

        // Then
        assertThat(result.rows()).isEqualTo(rows);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void scanLimit_rejectsANegativeCount(long rows) {
        // When / Then
        assertThatThrownBy(() -> ScanLimit.of(rows))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }
}
