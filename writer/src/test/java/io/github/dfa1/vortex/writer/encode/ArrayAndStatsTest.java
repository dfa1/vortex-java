package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The cascade asks every candidate's `expectedRatio` for a verdict through one [ArrayAndStats].
/// These pin the two properties it relies on: one scan serves every reader, and a non-primitive
/// input never reaches `ArrayStats.compute` (which has no ptype for it).
class ArrayAndStatsTest {

    @Test
    void stats_computedOnce_andSharedByEveryCaller() {
        // Given
        ArrayAndStats sut = new ArrayAndStats(DType.I64, new long[]{7, 7, 9}, StatsOptions.DISTINCT_AND_TOP);

        // When
        ArrayStats result = sut.stats();

        // Then: the same instance again, so a second candidate does not rescan the array
        assertThat(sut.stats()).isSameAs(result);
        assertThat(result.distinctCount()).isEqualTo(2);
        assertThat(result.mostFrequentBits()).isEqualTo(7L);
        assertThat(result.topFrequency()).isEqualTo(2);
    }

    @Test
    void stats_nonPrimitive_isEmpty() {
        // Given: strings go through the same competition, but no stats consumer reads them
        ArrayAndStats sut = new ArrayAndStats(DType.UTF8, new String[]{"a", "b"}, StatsOptions.DISTINCT_AND_TOP);

        // When
        ArrayStats result = sut.stats();

        // Then
        assertThat(result).isSameAs(ArrayStats.EMPTY);
    }
}
