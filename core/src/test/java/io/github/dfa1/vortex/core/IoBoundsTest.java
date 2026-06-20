package io.github.dfa1.vortex.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoBoundsTest {

    @Nested
    class CheckRange {

        @ParameterizedTest
        // off, len against a size-16 region — every in-bounds case, including the
        // exact end (off+len == size) and a zero-length slice at the boundary.
        @CsvSource({"0,16", "0,0", "16,0", "4,8", "15,1"})
        void acceptsInBoundsRange(long off, long len) {
            // Given a region of 16 bytes
            // When / Then no exception
            IoBounds.checkRange(off, len, 16);
        }

        @Test
        void rejectsNegativeOffset() {
            // Given / When / Then
            assertThatThrownBy(() -> IoBounds.checkRange(-1, 4, 16))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of bounds");
        }

        @Test
        void rejectsNegativeLength() {
            // Given / When / Then
            assertThatThrownBy(() -> IoBounds.checkRange(0, -1, 16))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void rejectsRangePastEnd() {
            // Given off+len = 17 > size 16
            // When / Then
            assertThatThrownBy(() -> IoBounds.checkRange(10, 7, 16))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void rejectsOverflowingLengthWithoutWrapping() {
            // Given a crafted huge length that would overflow off+len if added naively;
            // the check uses len > size - off precisely to stay overflow-safe.
            assertThatThrownBy(() -> IoBounds.checkRange(8, Long.MAX_VALUE, 16))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Slice {

        @Test
        void returnsRequestedSubRegion() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a 16-byte segment
                MemorySegment seg = arena.allocate(16);

                // When slicing the middle 8 bytes
                MemorySegment result = IoBounds.slice(seg, 4, 8);

                // Then the slice has the requested size
                assertThat(result.byteSize()).isEqualTo(8);
            }
        }

        @Test
        void throwsVortexExceptionNotJdkOnOverflow() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a 16-byte segment and an out-of-range request
                MemorySegment seg = arena.allocate(16);

                // When / Then the contract holds — VortexException, never IndexOutOfBoundsException
                assertThatThrownBy(() -> IoBounds.slice(seg, 0, 32))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class ToIntSize {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 1024, Integer.MAX_VALUE})
        void narrowsValuesWithinIntRange(long n) {
            // Given / When / Then
            assertThat(IoBounds.toIntSize(n)).isEqualTo((int) n);
        }

        @Test
        void rejectsValueAboveIntMax() {
            // Given a length one past the 2 GB ByteBuffer/array cap
            assertThatThrownBy(() -> IoBounds.toIntSize(Integer.MAX_VALUE + 1L))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("2 GB");
        }

        @Test
        void rejectsNegativeValue() {
            // Given a length that read back negative (e.g. a u32 stored as signed)
            assertThatThrownBy(() -> IoBounds.toIntSize(-1))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class CheckCount {

        @Test
        void delegatesToTheSameGuard() {
            // Given a valid count
            // When / Then it returns the narrowed value
            assertThat(IoBounds.checkCount(42)).isEqualTo(42);
        }

        @Test
        void rejectsOversizedCount() {
            // Given a crafted huge element count for a new T[n] allocation
            assertThatThrownBy(() -> IoBounds.checkCount(Long.MAX_VALUE))
                    .isInstanceOf(VortexException.class);
        }
    }
}
