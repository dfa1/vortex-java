package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for [SegmentBroadcast]. These verify the wrap-on-broadcast
/// semantics that protect downstream encoders/decoders from IOOB when a child
/// segment was produced by [ConstantEncoding] (single-element buffer).
class SegmentBroadcastTest {

    @ParameterizedTest(name = "{0} elements for {1} rows")
    @CsvSource({"4, 4", "5, 4", "1, 1000", "0, 0"})
    void capacity_fullOrConstantChild_isAccepted(long elements, long count) {
        // Given — a full child, a longer one, a constant child broadcast, and an empty child of an
        // empty array: the shapes a decoder may legally meet
        MemorySegment seg = MemorySegment.ofArray(new long[(int) elements]);

        // When
        long result = SegmentBroadcast.capacity(seg, 8, count);

        // Then
        assertThat(result).isEqualTo(elements);
    }

    @ParameterizedTest(name = "{0} elements for {1} rows")
    @CsvSource({"0, 1", "0, 1000", "2, 3", "999, 1000"})
    void capacity_emptyOrTruncatedChild_throws(long elements, long count) {
        // Given — an empty child of a non-empty array made every caller's `i % capacity` broadcast
        // divide by zero (a fuzzer finding in the dict decoder); a truncated one silently repeated
        // its values
        MemorySegment seg = MemorySegment.ofArray(new long[(int) elements]);

        // When / Then
        assertThatThrownBy(() -> SegmentBroadcast.capacity(seg, 8, count))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("child holds " + elements + " element(s) for a declared length of " + count);
    }

    @Test
    void elementOffset_wrapsConstantSegment() {
        try (Arena arena = Arena.ofConfined()) {
            // Given
            MemorySegment seg = arena.allocate(8);
            seg.set(VortexFormat.LE_LONG, 0, 99L);

            // When / Then
            assertThat(SegmentBroadcast.elementOffset(seg, 0, 8)).isZero();
            assertThat(SegmentBroadcast.elementOffset(seg, 5, 8)).isZero();
            assertThat(SegmentBroadcast.elementOffset(seg, 999_999, 8)).isZero();
            assertThat(SegmentBroadcast.capacity(seg, 8, 1_000_000)).isEqualTo(1L);
        }
    }

    @Test
    void elementOffset_passesThroughFullSegment() {
        try (Arena arena = Arena.ofConfined()) {
            // Given
            MemorySegment seg = arena.allocate(8 * 4);

            // When / Then
            for (long i = 0; i < 4; i++) {
                assertThat(SegmentBroadcast.elementOffset(seg, i, 8)).isEqualTo(i * 8);
            }
            assertThat(SegmentBroadcast.elementOffset(seg, 4, 8)).isZero();
            assertThat(SegmentBroadcast.capacity(seg, 8, 4)).isEqualTo(4L);
        }
    }

    @Test
    void broadcastCopy_replicatesSingleElement() {
        try (Arena arena = Arena.ofConfined()) {
            // Given
            MemorySegment src = arena.allocate(8);
            src.set(VortexFormat.LE_LONG, 0, 0xCAFEBABEL);
            MemorySegment dst = arena.allocate(8 * 5);

            // When
            SegmentBroadcast.broadcastCopy(src, dst, 5, 8);

            // Then
            for (long i = 0; i < 5; i++) {
                assertThat(dst.getAtIndex(VortexFormat.LE_LONG, i)).isEqualTo(0xCAFEBABEL);
            }
        }
    }
}
