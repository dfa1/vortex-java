package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArrayStatsTest {

    @Test
    void empty_returnsCanonicalInstance() {
        // Given / When / Then — empty() is a singleton; callers may identity-compare.
        assertThat(ArrayStats.empty()).isSameAs(ArrayStats.empty());
    }

    @Test
    void empty_hasAllNullComponents() {
        // Given / When
        ArrayStats sut = ArrayStats.empty();

        // Then
        assertThat(sut.min()).isNull();
        assertThat(sut.max()).isNull();
        assertThat(sut.sum()).isNull();
        assertThat(sut.isSorted()).isNull();
        assertThat(sut.isStrictSorted()).isNull();
        assertThat(sut.isConstant()).isNull();
        assertThat(sut.nullCount()).isNull();
        assertThat(sut.uncompressedSizeInBytes()).isNull();
        assertThat(sut.nanCount()).isNull();
    }
}
