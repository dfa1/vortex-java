package io.github.dfa1.vortex.reader.array;

import org.junit.jupiter.api.Test;

import static io.github.dfa1.vortex.encoding.DTypes.I64;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [LazyDateTimePartsLongArray]. Verifies the
/// `days * unitsPerDay + seconds * unitsPerSecond + subseconds`
/// reassembly across the supported time units, and the widening read path
/// that lets each child use whatever signed-integer ptype the encoder picked.
class LazyDateTimePartsLongArrayTest {

    @Test
    void millisecondsReassembly() {
        // Given 2 rows of (days, seconds_in_day, subseconds) for ms unit:
        // unitsPerSecond = 1_000; unitsPerDay = 86_400_000; row 0 -> 1_728_012_345_678
        LongArray days = longs(20_000L, 0L);
        LongArray seconds = longs(12_345L, 0L);
        LongArray subseconds = longs(678L, 0L);
        long unitsPerSecond = 1_000L;
        long unitsPerDay = 86_400L * unitsPerSecond;

        // When
        var sut = new LazyDateTimePartsLongArray(I64, 2,
                days, seconds, subseconds, unitsPerDay, unitsPerSecond);

        // Then
        assertThat(sut.getLong(0)).isEqualTo(
                20_000L * unitsPerDay + 12_345L * unitsPerSecond + 678L);
        assertThat(sut.getLong(1)).isZero();
    }

    @Test
    void widensFromNarrowerChildPtypes() {
        // Given days/seconds as I32, subseconds as I64 — encoder is free to pick widths
        IntArray days = ints(1);
        IntArray seconds = ints(2);
        LongArray subseconds = longs(3L);
        long ups = 1_000_000_000L;  // nanos
        long upd = 86_400L * ups;

        // When
        var sut = new LazyDateTimePartsLongArray(I64, 1, days, seconds, subseconds, upd, ups);

        // Then
        assertThat(sut.getLong(0)).isEqualTo(1L * upd + 2L * ups + 3L);
    }

    @Test
    void foldSumsAllRows() {
        // Given
        LongArray days = longs(1L, 2L, 3L);
        LongArray seconds = longs(0L, 0L, 0L);
        LongArray subseconds = longs(0L, 0L, 0L);
        long ups = 1L;
        long upd = 86_400L;
        var sut = new LazyDateTimePartsLongArray(I64, 3, days, seconds, subseconds, upd, ups);

        // When
        long result = sut.fold(0L, Long::sum);

        // Then — 1*86400 + 2*86400 + 3*86400 = 6*86400
        assertThat(result).isEqualTo(6L * upd);
    }
}
