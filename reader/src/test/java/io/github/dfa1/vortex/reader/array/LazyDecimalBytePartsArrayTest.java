package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyDecimalBytePartsArrayTest {

    @Test
    void getDecimal_i64Mantissa_reassemblesAtDtypeScale() {
        // Given — vortex.decimal_byte_parts with lower_part_count == 0 stores the
        // mantissa as a single signed-integer child. decimal(15, 2) means the i64
        // mantissa 4321 represents 43.21.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mspBuf = arena.allocate(24);
            mspBuf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 4321L);
            mspBuf.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, -100L);
            mspBuf.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, 0L);
            LongArray msp = new MaterializedLongArray(DType.I64, 3, mspBuf);
            DType.Decimal dec = new DType.Decimal((byte) 15, (byte) 2, false);
            LazyDecimalBytePartsArray sut = new LazyDecimalBytePartsArray(dec, 3, msp);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("43.21"));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-1.00"));
            assertThat(sut.getDecimal(2)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void getDecimal_widensFromNarrowerChildPtype() {
        // Given — the writer picks the narrowest ptype that fits, so a small-range
        // mantissa column may land as i32 (or narrower) even at high precision.
        // Without per-row ptype dispatch the reader would mis-decode anything
        // narrower than i64.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mspBuf = arena.allocate(12);
            mspBuf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 1234);
            mspBuf.set(ValueLayout.JAVA_INT_UNALIGNED, 4, -50);
            mspBuf.set(ValueLayout.JAVA_INT_UNALIGNED, 8, 0);
            IntArray msp = new MaterializedIntArray(DType.I32, 3, mspBuf);
            DType.Decimal dec = new DType.Decimal((byte) 15, (byte) 2, false);
            LazyDecimalBytePartsArray sut = new LazyDecimalBytePartsArray(dec, 3, msp);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(sut.getDecimal(1)).isEqualByComparingTo(new BigDecimal("-0.50"));
            assertThat(sut.getDecimal(2)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void getDecimal_nullCellInMaskedChild_throws() {
        // Given — nullable decimal columns wrap the mantissa child in MaskedArray.
        // Without honoring the validity bitmap, getDecimal would happily return
        // a garbage BigDecimal for a row whose mantissa bytes are undefined.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mspBuf = arena.allocate(16);
            mspBuf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 1234L);
            mspBuf.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, 9999L);
            LongArray msp = new MaterializedLongArray(DType.I64, 2, mspBuf);

            MemorySegment validityBuf = arena.allocate(1);
            // bit 0 set = index 0 valid; bit 1 clear = index 1 null
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0001);
            BoolArray validity = new MaterializedBoolArray(DType.BOOL, 2, validityBuf);

            MaskedArray masked = new MaskedArray(msp, validity);
            DType.Decimal dec = new DType.Decimal((byte) 15, (byte) 2, true);
            LazyDecimalBytePartsArray sut = new LazyDecimalBytePartsArray(dec, 2, masked);

            // When / Then
            assertThat(sut.getDecimal(0)).isEqualByComparingTo(new BigDecimal("12.34"));
            assertThatThrownBy(() -> sut.getDecimal(1))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("null cell at index 1");
        }
    }

    @Test
    void getDecimal_indexOutOfBounds_throws() {
        // Given — explicit bounds check guards against silent garbage reads
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mspBuf = arena.allocate(8);
            LongArray msp = new MaterializedLongArray(DType.I64, 1, mspBuf);
            DType.Decimal dec = new DType.Decimal((byte) 4, (byte) 0, false);
            LazyDecimalBytePartsArray sut = new LazyDecimalBytePartsArray(dec, 1, msp);

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
            MemorySegment mspBuf = arena.allocate(8);
            LongArray msp = new MaterializedLongArray(DType.I64, 1, mspBuf);
            LazyDecimalBytePartsArray sut = new LazyDecimalBytePartsArray(
                    DType.I64, 1, msp);

            // When / Then
            assertThatThrownBy(() -> sut.getDecimal(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("non-decimal");
        }
    }
}
