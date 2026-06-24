package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyDecimalArrayTest {


    @Test
    void getDecimal_i64Buffer_appliesDtypeScale() {
        // Given — vortex.decimal stores one LE two's-complement integer per row;
        // decimal(15, 2) with mantissa 4321 represents 43.21.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(24);
            buf.set(PTypeIO.LE_LONG, 0, 4321L);
            buf.set(PTypeIO.LE_LONG, 8, -100L);
            buf.set(PTypeIO.LE_LONG, 16, 0L);
            DType.Decimal dec = new DType.Decimal((byte) 15, (byte) 2, false);
            LazyDecimalArray sut = new LazyDecimalArray(dec, 3, buf, 8);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("43.21"));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-1.00"));
            assertThat(sut.getDecimal(2)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void getDecimal_i8I16I32Widths_signExtend() {
        // Given — the writer picks the narrowest width that fits each precision tier.
        // Without per-width LE dispatch, narrow rows would mis-decode (especially negatives,
        // which need sign extension at the chosen width).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf1 = arena.allocate(3);
            buf1.set(ValueLayout.JAVA_BYTE, 0, (byte) 12);
            buf1.set(ValueLayout.JAVA_BYTE, 1, (byte) -5);
            buf1.set(ValueLayout.JAVA_BYTE, 2, (byte) 0);
            DType.Decimal dec1 = new DType.Decimal((byte) 2, (byte) 1, false);
            LazyDecimalArray sut1 = new LazyDecimalArray(dec1, 3, buf1, 1);
            assertThat(sut1.getDecimal(0)).isEqualByComparingTo(new BigDecimal("1.2"));
            assertThat(sut1.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.5"));

            MemorySegment buf2 = arena.allocate(4);
            buf2.set(PTypeIO.LE_SHORT, 0, (short) 1234);
            buf2.set(PTypeIO.LE_SHORT, 2, (short) -7);
            DType.Decimal dec2 = new DType.Decimal((byte) 4, (byte) 2, false);
            LazyDecimalArray sut2 = new LazyDecimalArray(dec2, 2, buf2, 2);
            assertThat(sut2.getDecimal(0)).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(sut2.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.07"));

            MemorySegment buf4 = arena.allocate(8);
            buf4.set(PTypeIO.LE_INT, 0, 999_999);
            buf4.set(PTypeIO.LE_INT, 4, -1);
            DType.Decimal dec4 = new DType.Decimal((byte) 9, (byte) 3, false);
            LazyDecimalArray sut4 = new LazyDecimalArray(dec4, 2, buf4, 4);
            assertThat(sut4.getDecimal(0)).isEqualByComparingTo(new BigDecimal("999.999"));
            assertThat(sut4.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.001"));
        }
    }

    @Test
    void getDecimal_i128Buffer_reassemblesBigEndianMantissa() {
        // Given — decimal(38, _) writers emit 16-byte LE two's-complement; the reader
        // reverses byte order into BigInteger's big-endian constructor. This test pins
        // the path that allocates a small heap buffer (only fires for the 16-byte tier).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(32);
            BigInteger pos = new BigInteger("123456789012345678901234567890");
            BigInteger neg = pos.negate();
            byte[] posBe = pos.toByteArray();
            byte[] negBe = neg.toByteArray();
            for (int k = 0; k < 16; k++) {
                byte pb = k < posBe.length ? posBe[posBe.length - 1 - k] : 0;
                byte nb = k < negBe.length ? negBe[negBe.length - 1 - k] : (byte) (neg.signum() < 0 ? 0xFF : 0);
                buf.set(ValueLayout.JAVA_BYTE, k, pb);
                buf.set(ValueLayout.JAVA_BYTE, 16 + k, nb);
            }
            DType.Decimal dec = new DType.Decimal((byte) 38, (byte) 0, false);
            LazyDecimalArray sut = new LazyDecimalArray(dec, 2, buf, 16);

            // When / Then
            assertThat(sut.getDecimal(0).toBigInteger()).isEqualTo(pos);
            assertThat(sut.getDecimal(1).toBigInteger()).isEqualTo(neg);
        }
    }

    @Test
    void getDecimal_indexOutOfBounds_throws() {
        // Given — explicit bounds check guards against silent garbage reads
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            DType.Decimal dec = new DType.Decimal((byte) 4, (byte) 0, false);
            LazyDecimalArray sut = new LazyDecimalArray(dec, 1, buf, 8);

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
        // Given — guards against constructing the record with the wrong parent dtype
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            LazyDecimalArray sut = new LazyDecimalArray(
                    DType.I64, 1, buf, 8);

            // When / Then
            assertThatThrownBy(() -> sut.getDecimal(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-decimal");
        }
    }

    @Test
    void getDecimal_unsupportedByteWidth_throws() {
        // Given — only 1/2/4/8/16 are valid widths per DecimalType metadata enum
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            DType.Decimal dec = new DType.Decimal((byte) 4, (byte) 0, false);
            LazyDecimalArray sut = new LazyDecimalArray(dec, 1, buf, 3);

            // When / Then
            assertThatThrownBy(() -> sut.getDecimal(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported element width");
        }
    }
}
