package io.github.dfa1.vortex.reader.array;



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
            GenericArray clamped = sut.limited(4);

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
            assertThat(sut.limited(10)).isSameAs(sut);
        }
    }

    @Test
    void withLength_zero_returnsEmptyView() {
        // Given — boundary case: truncating to zero must still produce a valid
        // GenericArray (length() == 0) rather than throw
        try (Arena arena = Arena.ofConfined()) {
            GenericArray sut = new GenericArray(DTYPE, 5, arena.allocate(40));

            // When
            GenericArray clamped = sut.limited(0);

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
            assertThatThrownBy(() -> sut.limited(4))
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
            assertThatThrownBy(() -> sut.limited(-1))
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
    void getDecimal_i128Buffer_decodesWideMantissa() {
        // Given — decimal(38,4) stores mantissas wider than i64; vortex.decimal
        // writes 16-byte little-endian two's-complement. Two values: 2^70 (way
        // above I64.MAX) and -2^70 anchor the high-precision path the
        // narrower-width tests never exercise.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(32);
            java.math.BigInteger pos = java.math.BigInteger.TWO.pow(70);
            java.math.BigInteger neg = pos.negate();
            writeI128Le(buf, 0, pos);
            writeI128Le(buf, 16, neg);
            DType.Decimal dec = new DType.Decimal((byte) 38, (byte) 4, false);
            GenericArray sut = new GenericArray(dec, 2, buf);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal(pos, 4));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal(neg, 4));
        }
    }

    private static void writeI128Le(MemorySegment buf, long offset, java.math.BigInteger value) {
        // BigInteger.toByteArray() returns minimum-length big-endian two's-complement.
        // Pad / sign-extend to 16 bytes, then reverse into the little-endian wire slot.
        byte[] be = value.toByteArray();
        byte[] le16 = new byte[16];
        // sign-extend pad in big-endian form
        byte sign = (byte) (value.signum() < 0 ? 0xFF : 0x00);
        for (int i = 0; i < 16; i++) {
            le16[15 - i] = sign;
        }
        for (int i = 0; i < be.length && i < 16; i++) {
            le16[i] = be[be.length - 1 - i];
        }
        for (int i = 0; i < 16; i++) {
            buf.set(ValueLayout.JAVA_BYTE, offset + i, le16[i]);
        }
    }

    @Test
    void getDecimal_widthDerivedFromBufferNotPrecision() {
        // Given — decimal(15,2) is precision 15 (≤18 → "should" be I64), but
        // vortex.decimal stores at whatever valuesType the encoder picked. A
        // narrower width fits if all values are small. The old precision-based
        // table picked 8 bytes here and read garbage. The current impl derives
        // width from buffer.byteSize / length, so storing 3 I32 values at the
        // same precision 15 decodes correctly.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(12); // 3 × 4 bytes (I32 mantissa)
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 1234);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 4, -50);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 8, 0);
            DType.Decimal dec = new DType.Decimal((byte) 15, (byte) 2, false);
            GenericArray sut = new GenericArray(dec, 3, buf);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.50"));
            assertThat(sut.getDecimal(2)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void getDecimal_unalignedBufferSize_throws() {
        // Given — buffer size not a clean multiple of length means we can't
        // derive a sensible per-element width; fail fast rather than silently
        // reading garbage from a half-element offset.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(7); // not divisible by length=2
            DType.Decimal dec = new DType.Decimal((byte) 4, (byte) 0, false);
            GenericArray sut = new GenericArray(dec, 2, buf);

            // When / Then
            assertThatThrownBy(() -> sut.getDecimal(0))
                    .isInstanceOf(io.github.dfa1.vortex.core.VortexException.class)
                    .hasMessageContaining("not a multiple");
        }
    }

    @Test
    void getDecimal_indexOutOfBounds_throws() {
        // Given — explicit bounds check guards against silent garbage reads
        // when callers don't respect length()
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            DType.Decimal dec = new DType.Decimal((byte) 4, (byte) 0, false);
            GenericArray sut = new GenericArray(dec, 1, buf);

            // When / Then
            assertThatThrownBy(() -> sut.getDecimal(-1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> sut.getDecimal(1))
                    .isInstanceOf(IndexOutOfBoundsException.class)
                    .hasMessageContaining("out of bounds");
            assertThatThrownBy(() -> sut.getDecimal(Long.MAX_VALUE))
                    .isInstanceOf(IndexOutOfBoundsException.class);
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
