package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanOptionsTest {

    @Test
    void withLimit_setsLimit() {
        // Given / When
        ScanOptions sut = ScanOptions.all().withLimit(10);

        // Then
        assertThat(sut.limit()).isEqualTo(10);
        assertThat(sut.hasLimit()).isTrue();
        assertThat(sut.columns()).isEmpty();
        assertThat(sut.rowFilter()).isNull();
    }

    @Test
    void withColumns_setsProjection() {
        // Given / When
        ScanOptions sut = ScanOptions.all().withColumns("price", "qty");

        // Then
        assertThat(sut.columns()).containsExactly("price", "qty");
        assertThat(sut.hasProjection()).isTrue();
        assertThat(sut.limit()).isEqualTo(ScanOptions.NO_LIMIT);
    }

    @Test
    void withFilter_setsFilter() {
        // Given
        RowFilter filter = RowFilter.gte("price", 100);

        // When
        ScanOptions sut = ScanOptions.all().withFilter(filter);

        // Then
        assertThat(sut.rowFilter()).isEqualTo(filter);
        assertThat(sut.hasFilter()).isTrue();
    }

    @Test
    void fluent_chainingPreservesAllFields() {
        // Given / When
        ScanOptions sut = ScanOptions.all()
                                  .withColumns("price", "qty")
                                  .withLimit(10)
                                  .withFilter(RowFilter.gte("price", 50));

        // Then
        assertThat(sut.columns()).containsExactly("price", "qty");
        assertThat(sut.limit()).isEqualTo(10);
        assertThat(sut.hasFilter()).isTrue();
    }

    @Test
    void withMethods_doNotMutateOriginal() {
        // Given
        ScanOptions original = ScanOptions.all();

        // When
        original.withLimit(5).withColumns("x");

        // Then
        assertThat(original.limit()).isEqualTo(ScanOptions.NO_LIMIT);
        assertThat(original.columns()).isEmpty();
    }
}
