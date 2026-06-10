package io.github.dfa1.vortex.core;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySegmentsTest {

    private final MemorySegment sut = MemorySegment.ofArray(new byte[16]);

    @Test
    void inRangeSliceReturnsExpectedRegion() {
        // Given valid offset+length inside the 16-byte backing array.

        // When
        MemorySegment slice = MemorySegments.slice(sut, 4, 8, "test region");

        // Then
        assertThat(slice.byteSize()).isEqualTo(8);
    }

    @Test
    void zeroLengthAtEndIsAllowed() {
        // Given — offset at the end, zero-length. The JDK permits this; we must too.

        // When + Then
        assertThat(MemorySegments.slice(sut, 16, 0, "tail").byteSize()).isEqualTo(0);
    }

    @Test
    void negativeOffsetThrowsVortexException() {
        // Given — adversarial offset from a malformed file.
        // Without the wrapper, MemorySegment.asSlice throws IndexOutOfBoundsException —
        // not VortexException — breaking the contract documented in SECURITY.md.

        // When + Then
        assertThatThrownBy(() -> MemorySegments.slice(sut, -1, 4, "region"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("region")
                .hasMessageContaining("negative offset");
    }

    @Test
    void negativeLengthThrowsVortexException() {
        // Given — adversarial length.

        // When + Then
        assertThatThrownBy(() -> MemorySegments.slice(sut, 0, -1, "region"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("region")
                .hasMessageContaining("negative length");
    }

    @Test
    void offsetPlusLengthBeyondSegmentSizeThrows() {
        // Given — 16-byte buffer, request 12 bytes starting at offset 8.

        // When + Then
        assertThatThrownBy(() -> MemorySegments.slice(sut, 8, 12, "blob"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("blob")
                .hasMessageContaining("exceeds segment size 16");
    }

    @Test
    void lengthAloneBiggerThanSegmentThrows() {
        // Given — len > segSize even with off=0.

        // When + Then
        assertThatThrownBy(() -> MemorySegments.slice(sut, 0, 17, "blob"))
                .isInstanceOf(VortexException.class);
    }

    @Test
    void overflowingOffsetPlusLengthRejected() {
        // Given — adversarial values designed to overflow a naive `off + len` computation.
        // (off + len) wraps to a small positive number, which would pass a naive
        // `off + len > segSize` check. The wrapper's overflow-safe form catches it.
        long off = Long.MAX_VALUE - 1;
        long len = 100;

        // When + Then
        assertThatThrownBy(() -> MemorySegments.slice(sut, off, len, "blob"))
                .isInstanceOf(VortexException.class);
    }
}
