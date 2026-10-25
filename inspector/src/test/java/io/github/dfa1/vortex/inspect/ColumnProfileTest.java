package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnProfileTest {

    @Test
    void fold_combinesZonesIntoColumnTotals() {
        // Given — two zones of a 10-row column, 1 null between them
        List<ArrayStats> zones = List.of(stats(3L, 9L, 30L, 0L), stats(1L, 5L, 15L, 1L));

        // When
        ColumnProfile result = ColumnProfile.fold("x", DType.I64, 10, zones, List.of());

        // Then
        assertThat(result.min()).isEqualTo(1L);
        assertThat(result.max()).isEqualTo(9L);
        assertThat(result.sum()).isEqualTo(45L);
        assertThat(result.nullCount()).isEqualTo(1L);
        assertThat(result.mean()).isEqualTo(5.0);
    }

    @Test
    void fold_skipsAllNullZoneInsteadOfLosingMinMax() {
        // Given — Rust leaves min/max/sum absent on a zone holding only nulls; that zone says nothing
        // about the column's range, so it must not make the whole column's min/max unknown
        ArrayStats allNull = new ArrayStats(null, false, null, false, null, null, null, null, 4L, null, null);
        List<ArrayStats> zones = List.of(stats(2L, 7L, 9L, 0L), allNull);

        // When
        ColumnProfile result = ColumnProfile.fold("x", DType.I64, 6, zones, List.of());

        // Then
        assertThat(result.min()).isEqualTo(2L);
        assertThat(result.max()).isEqualTo(7L);
        assertThat(result.nullCount()).isEqualTo(4L);
    }

    @Test
    void fold_inexactBoundIsNotReportedAsTheMinimum() {
        // Given — a truncated string bound brackets the values but is not one of them
        ArrayStats bounded = new ArrayStats("ab", false, "zz", true, null, null, null, null, 0L, null, null);

        // When
        ColumnProfile result = ColumnProfile.fold("s", DType.UTF8, 3, List.of(bounded), List.of());

        // Then
        assertThat(result.min()).isNull();
        assertThat(result.max()).isEqualTo("zz");
    }

    @Test
    void fold_oneZoneMissingSumLeavesColumnSumUnknown() {
        // Given — a partial sum would print a wrong mean, not an approximate one
        List<ArrayStats> zones = List.of(stats(1L, 2L, 3L, 0L), stats(1L, 2L, null, 0L));

        // When
        ColumnProfile result = ColumnProfile.fold("x", DType.I64, 4, zones, List.of());

        // Then
        assertThat(result.sum()).isNull();
        assertThat(result.mean()).isNull();
    }

    @Test
    void fold_overflowingIntegerSumIsUnknownNotWrapped() {
        // Given
        List<ArrayStats> zones = List.of(stats(0L, 1L, Long.MAX_VALUE, 0L), stats(0L, 1L, 1L, 0L));

        // When
        ColumnProfile result = ColumnProfile.fold("x", DType.I64, 2, zones, List.of());

        // Then
        assertThat(result.sum()).isNull();
    }

    @Test
    void fold_noStatsAtAllMeansEveryAggregateUnknown() {
        // Given / When
        ColumnProfile result = ColumnProfile.fold("x", DType.I64, 0, List.of(), List.of());

        // Then — not "0 nulls": nothing was recorded
        assertThat(result.nullCount()).isNull();
        assertThat(result.min()).isNull();
        assertThat(result.sum()).isNull();
    }

    @Test
    void mean_excludesNullsAndNans() {
        // Given — 10 rows, 2 null, 3 NaN: the sum covers the other 5
        ColumnProfile sut = new ColumnProfile("f", DType.F64, 10, 2L, 3L, 1.0, 4.0, 10.0, List.of());

        // When
        Double result = sut.mean();

        // Then
        assertThat(result).isEqualTo(2.0);
    }

    @Test
    void mean_allNullColumnHasNoMean() {
        // Given
        ColumnProfile sut = new ColumnProfile("f", DType.F64, 3, 3L, 0L, null, null, 0.0, List.of());

        // When
        Double result = sut.mean();

        // Then — not a division by zero, and not NaN
        assertThat(result).isNull();
    }

    private static ArrayStats stats(Long min, Long max, Long sum, Long nulls) {
        return new ArrayStats(min, true, max, true, sum, null, null, null, nulls, null, null);
    }
}
