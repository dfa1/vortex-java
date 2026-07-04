package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [SegmentBroadcast]. These verify the wrap-on-broadcast
/// semantics that protect downstream encoders/decoders from IOOB when a child
/// segment was produced by [ConstantEncoding] (single-element buffer).
class SegmentBroadcastTest {

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
            assertThat(SegmentBroadcast.capacity(seg, 8)).isEqualTo(1L);
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
            assertThat(SegmentBroadcast.capacity(seg, 8)).isEqualTo(4L);
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
