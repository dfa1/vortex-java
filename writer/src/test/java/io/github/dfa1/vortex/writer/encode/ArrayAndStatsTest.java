package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The cascade asks every candidate's `expectedRatio` for a verdict through one [ArrayAndStats].
/// These pin the two properties it relies on: one scan serves every reader, and a non-primitive
/// input never reaches `ArrayStats.compute` (which has no ptype for it).
class ArrayAndStatsTest {

    private static final StatsOptions ALL_STATS = StatsOptions.defaults().withCountDistinct(true).withTrackMostFrequent(true);

    @Test
    void stats_computedOnce_andSharedByEveryCaller() {
        // Given
        ArrayAndStats sut = new ArrayAndStats(DType.I64, new long[]{7, 7, 9}, ALL_STATS);

        // When
        ArrayStats result = sut.stats();

        // Then: the same instance again, so a second candidate does not rescan the array
        assertThat(sut.stats()).isSameAs(result);
        assertThat(result.distinctCount()).isEqualTo(2);
        assertThat(result.mostFrequentBits()).isEqualTo(7L);
        assertThat(result.topFrequency()).isEqualTo(2);
    }

    @Test
    void stats_nullable_skipNulls() {
        // Given: the nulls hold 0, below every valid value, and sit between equal values, as
        // Rust's stats see them only as gaps: a null continues the run before it
        long[] values = {5, 0, 5, 9, 0, 9, 9};
        boolean[] validity = {true, false, true, true, false, true, true};
        ArrayAndStats sut = new ArrayAndStats(DType.I64, new NullableData(values, validity), ALL_STATS);

        // When
        ArrayStats result = sut.stats();

        // Then: 5 valid values in 2 runs, 0 never counted
        assertThat(result.valueCount()).isEqualTo(5);
        assertThat(result.distinctCount()).isEqualTo(2);
        assertThat(result.min()).isEqualTo(5L);
        assertThat(result.max()).isEqualTo(9L);
        assertThat(result.averageRunLength()).isEqualTo(2);
        assertThat(sut.length()).isEqualTo(7);
        assertThat(sut.nullCount()).isEqualTo(2);
    }

    @Test
    void stats_nonPrimitive_isEmpty() {
        // Given: strings go through the same competition, but no stats consumer reads them
        ArrayAndStats sut = new ArrayAndStats(DType.UTF8, new String[]{"a", "b"}, ALL_STATS);

        // When
        ArrayStats result = sut.stats();

        // Then
        assertThat(result).isSameAs(ArrayStats.EMPTY);
    }
}
