package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericArrayTest {

    private static final DType DTYPE = new DType.Primitive(PType.I64, false);

    @Test
    void withLength_shorterLength_returnsClampedView() {
        // Given — full-size array of 10 elements
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(80);
            GenericArray sut = new GenericArray(DTYPE, 10, seg);

            // When
            GenericArray clamped = sut.withLength(4);

            // Then — length reflects new bound; buffer is reused (no copy)
            assertThat(clamped.length()).isEqualTo(4);
            assertThat(clamped.dtype()).isEqualTo(DTYPE);
        }
    }

    @Test
    void withLength_sameLength_returnsSameInstance() {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            GenericArray sut = new GenericArray(DTYPE, 10, arena.allocate(80));

            // When / Then — no-op short-circuits to avoid wrapper allocation
            assertThat(sut.withLength(10)).isSameAs(sut);
        }
    }

    @Test
    void withLength_zero_returnsEmptyView() {
        // Given — boundary case: truncating to zero must still produce a valid
        // GenericArray (length() == 0) rather than throw
        try (Arena arena = Arena.ofConfined()) {
            GenericArray sut = new GenericArray(DTYPE, 5, arena.allocate(40));

            // When
            GenericArray clamped = sut.withLength(0);

            // Then
            assertThat(clamped.length()).isZero();
        }
    }

    @Test
    void withLength_greaterThanCurrent_throws() {
        // Given — protects against silently extending past the backing buffer
        try (Arena arena = Arena.ofConfined()) {
            GenericArray sut = new GenericArray(DTYPE, 3, arena.allocate(24));

            // When / Then
            assertThatThrownBy(() -> sut.withLength(4))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
        }
    }

    @Test
    void withLength_negative_throws() {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            GenericArray sut = new GenericArray(DTYPE, 3, arena.allocate(24));

            // When / Then
            assertThatThrownBy(() -> sut.withLength(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
        }
    }
}
