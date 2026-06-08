package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionsTest {

    private static final DType.Primitive I32 = new DType.Primitive(PType.I32, false);
    private static final DType DATE_DTYPE = new DType.Extension(Extensions.DATE, I32, null, false);

    @Test
    void localDate_zeroIsUnixEpoch() {
        // Given — Arrow-compatible: 0 == 1970-01-01
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
            IntArray sut = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThat(Extensions.localDate(sut, 0)).isEqualTo(LocalDate.of(1970, 1, 1));
        }
    }

    @Test
    void localDate_tpchSampleValue_matchesExpected() {
        // Given — anchor against a known TPC-H value: 9538 = 1996-02-12.
        // Catches accidental epoch-shift regressions (e.g. days-since-2000).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
            IntArray sut = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThat(Extensions.localDate(sut, 0)).isEqualTo(LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void localDate_negativeDays_returnsPreEpoch() {
        // Given — defensive: integer storage is signed, so pre-1970 dates must work
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, -1);
            IntArray sut = new IntArray(DATE_DTYPE, 1, buf);

            // When / Then
            assertThat(Extensions.localDate(sut, 0)).isEqualTo(LocalDate.of(1969, 12, 31));
        }
    }

    @Test
    void localDate_nonDateDtype_throws() {
        // Given — guards against silent misinterpretation (e.g. plain I32 as days)
        try (Arena arena = Arena.ofConfined()) {
            IntArray sut = new IntArray(I32, 1, arena.allocate(4));

            // When / Then
            assertThatThrownBy(() -> Extensions.localDate(sut, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-date");
        }
    }

    @Test
    void localDate_unsupportedStorage_throws() {
        // Given — a date dtype on top of a varbin array makes no semantic sense
        try (Arena arena = Arena.ofConfined()) {
            VarBinArray badStorage = new VarBinArray(DATE_DTYPE, 1,
                    arena.allocate(0), arena.allocate(8), PType.I32);

            // When / Then
            assertThatThrownBy(() -> Extensions.localDate(badStorage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported storage");
        }
    }
}
