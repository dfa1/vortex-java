package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for {@link LazyDateTimePartsLongArray}. Verifies the
/// {@code days * unitsPerDay + seconds * unitsPerSecond + subseconds}
/// reassembly across the supported time units, and the widening read path
/// that lets each child use whatever signed-integer ptype the encoder picked.
class LazyDateTimePartsLongArrayTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType I32 = new DType.Primitive(PType.I32, false);
    // Decoder constructs records carrying the parent Extension dtype; use I64 here
    // as a stand-in since the record never inspects dtype semantics directly.

    @Test
    void millisecondsReassembly() {
        // Given 2 rows of arbitrary (days, seconds_in_day, subseconds) for ms unit.
        // unitsPerSecond = 1_000; unitsPerDay = 86_400_000.
        // Row 0: days=20_000 -> 2024-12-31 area, seconds=12345, subseconds=678 -> 1_728_012_345_678
        try (Arena arena = Arena.ofConfined()) {
            LongArray days = longArray(arena, 20_000L, 0L);
            LongArray seconds = longArray(arena, 12_345L, 0L);
            LongArray subseconds = longArray(arena, 678L, 0L);
            long unitsPerSecond = 1_000L;
            long unitsPerDay = 86_400L * unitsPerSecond;

            var sut = new LazyDateTimePartsLongArray(I64, 2,
                    days, seconds, subseconds, unitsPerDay, unitsPerSecond);

            assertThat(sut.getLong(0)).isEqualTo(
                    20_000L * unitsPerDay + 12_345L * unitsPerSecond + 678L);
            assertThat(sut.getLong(1)).isZero();
        }
    }

    @Test
    void widensFromNarrowerChildPtypes() {
        // Days as I32, seconds as I32, subseconds as I64 — encoder is free to pick.
        try (Arena arena = Arena.ofConfined()) {
            IntArray days = intArray(arena, 1);
            IntArray seconds = intArray(arena, 2);
            LongArray subseconds = longArray(arena, 3L);
            long ups = 1_000_000_000L;  // nanos
            long upd = 86_400L * ups;

            var sut = new LazyDateTimePartsLongArray(I64, 1,
                    days, seconds, subseconds, upd, ups);

            assertThat(sut.getLong(0)).isEqualTo(1L * upd + 2L * ups + 3L);
        }
    }

    @Test
    void foldSumsAllRows() {
        try (Arena arena = Arena.ofConfined()) {
            LongArray days = longArray(arena, 1L, 2L, 3L);
            LongArray seconds = longArray(arena, 0L, 0L, 0L);
            LongArray subseconds = longArray(arena, 0L, 0L, 0L);
            long ups = 1L;
            long upd = 86_400L;

            var sut = new LazyDateTimePartsLongArray(I64, 3,
                    days, seconds, subseconds, upd, ups);

            long sum = sut.fold(0L, Long::sum);
            // 1*86400 + 2*86400 + 3*86400 = 6*86400
            assertThat(sum).isEqualTo(6L * upd);
        }
    }

    private static LongArray longArray(Arena arena, long... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, vs[i]);
        }
        return new MaterializedLongArray(I64, vs.length, seg.asReadOnly());
    }

    private static IntArray intArray(Arena arena, int... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, vs[i]);
        }
        return new MaterializedIntArray(I32, vs.length, seg.asReadOnly());
    }
}
