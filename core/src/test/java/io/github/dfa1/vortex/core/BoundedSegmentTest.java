package io.github.dfa1.vortex.core;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedSegmentTest {

    private final BoundedSegment sut = new BoundedSegment(
            MemorySegment.ofArray(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}),
            "test region");

    @Test
    void inRangeSliceReturnsExpectedRegion() {
        // Given the 16-byte test region.

        // When
        BoundedSegment child = sut.slice(4, 8, "child");

        // Then — the slice carries its own context label, used in nested error messages.
        assertThat(child.byteSize()).isEqualTo(8);
        assertThat(child.context()).isEqualTo("child");
    }

    @Test
    void badSliceThrowsVortexExceptionLabelledByParent() {
        // Given — adversarial slice on the bounded region. The parent's context label
        // ("test region") surfaces in the error so the caller knows which structure
        // was being parsed when the bad offset arrived.

        // When + Then
        assertThatThrownBy(() -> sut.slice(20, 4, "child"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("test region");
    }

    @Test
    void primitiveReadsAreBoundsChecked() {
        // Given — getIntLE at offset 12 needs 4 bytes (12..16), valid.

        // When + Then
        assertThat(sut.getIntLE(12)).isNotZero();

        // Out-of-range read throws VortexException, not IOOBE.
        assertThatThrownBy(() -> sut.getIntLE(13))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("test region");
    }

    @Test
    void zeroLengthAtEndIsAllowed() {
        // Given — slice at the end with zero length. JDK permits this; the wrapper must too.

        // When + Then — does not throw; child region has byteSize 0.
        assertThat(sut.slice(16, 0, "tail").byteSize()).isEqualTo(0);
    }

    @Test
    void negativeOffsetThrowsVortexException() {
        // Given — adversarial offset from a malformed file.

        // When + Then
        assertThatThrownBy(() -> sut.slice(-1, 4, "child"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("negative offset");
    }

    @Test
    void negativeLengthThrowsVortexException() {
        // Given — adversarial length.

        // When + Then
        assertThatThrownBy(() -> sut.slice(0, -1, "child"))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("negative length");
    }

    @Test
    void lengthAloneBiggerThanSegmentThrows() {
        // Given — len > segSize with off=0; the wrapper rejects before any subtraction.

        // When + Then
        assertThatThrownBy(() -> sut.slice(0, 17, "child"))
                .isInstanceOf(VortexException.class);
    }

    @Test
    void overflowingOffsetPlusLengthRejected() {
        // Given — adversarial values designed to overflow a naive `off + len` computation:
        // (off + len) wraps to a small positive number, which would pass a naive
        // `off + len > segSize` check. The overflow-safe form catches it.

        // When + Then
        assertThatThrownBy(() -> sut.slice(Long.MAX_VALUE - 1, 100, "child"))
                .isInstanceOf(VortexException.class);
    }

    @Test
    void unwrapForSubParserReturnsRawSegment() {
        // Given — explicit trust transfer documented by the reason string. The unwrapped
        // segment is the same instance as the backing seg(); callers re-validate bounds
        // in their own cursor (e.g. ProtoReader).

        // When
        MemorySegment raw = sut.unwrapForSubParser("test sub-parser");

        // Then
        assertThat(raw).isSameAs(sut.seg());
        assertThat(raw.byteSize()).isEqualTo(16);
    }
}
