package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;

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

    @Test
    void getDecimal_i64Buffer_decodesMantissaScaledByDtype() {
        // Given — decimal(15,2): precision 15 → 8-byte (I64) mantissa; values
        // 1234 / -50 / 0 should render as 12.34 / -0.50 / 0.00.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(24);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1234L);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, -50L);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, 0L);
            DType.Decimal dec = new DType.Decimal((byte) 15, (byte) 2, false);
            GenericArray sut = new GenericArray(dec, 3, buf);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.50"));
            assertThat(sut.getDecimal(2)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void getDecimal_smallPrecisionUsesNarrowerBuffer() {
        // Given — decimal(4,1): precision 4 → 2-byte (I16) mantissa
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_SHORT_UNALIGNED, 0, (short) 99);
            buf.set(ValueLayout.JAVA_SHORT_UNALIGNED, 2, (short) -1);
            DType.Decimal dec = new DType.Decimal((byte) 4, (byte) 1, false);
            GenericArray sut = new GenericArray(dec, 2, buf);

            // When / Then — 99 / 10 = 9.9; -1 / 10 = -0.1 (signed extension matters)
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("9.9"));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.1"));
        }
    }

    @Test
    void getDecimal_nonDecimalDtype_throws() {
        // Given — guards against silently returning garbage on misuse
        try (Arena arena = Arena.ofConfined()) {
            GenericArray sut = new GenericArray(DTYPE, 1, arena.allocate(8));

            // When / Then
            assertThatThrownBy(() -> sut.getDecimal(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-decimal");
        }
    }
}
