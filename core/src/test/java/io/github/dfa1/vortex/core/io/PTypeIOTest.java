package io.github.dfa1.vortex.core.io;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_DOUBLE;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for [PTypeIO]'s primitive write (`set`) and bulk-copy (`copyArray`) helpers, which
/// underpin every encoding's segment I/O. `set` must narrow / bit-reinterpret a `long bits` value
/// to each ptype's carrier and write it little-endian; `copyArray` must allocate exactly
/// `count * byteSize` bytes and convert host-order array data to a little-endian segment.
class PTypeIOTest {

    private static MemorySegment buffer() {
        return MemorySegment.ofArray(new byte[8]);
    }

    // ── set: narrowing integer carriers ──────────────────────────────────────────

    @Test
    void set_i64_writesFullLong() {
        // Given
        MemorySegment sut = buffer();

        // When
        PTypeIO.set(sut, 0, PType.I64, 0x0123_4567_89AB_CDEFL);

        // Then
        assertThat(sut.get(LE_LONG, 0)).isEqualTo(0x0123_4567_89AB_CDEFL);
    }

    @Test
    void set_i32_narrowsToLow32Bits() {
        // Given — high 32 bits set; the I32 carrier must keep only the low word
        MemorySegment sut = buffer();

        // When
        PTypeIO.set(sut, 0, PType.I32, 0xFFFF_FFFFL << 32 | 0x1234_5678L);

        // Then
        assertThat(sut.get(LE_INT, 0)).isEqualTo(0x1234_5678);
    }

    @Test
    void set_i16_narrowsToLow16Bits() {
        // Given
        MemorySegment sut = buffer();

        // When
        PTypeIO.set(sut, 0, PType.I16, 0xFFFF_0000L | 0xABCDL);

        // Then
        assertThat(sut.get(LE_SHORT, 0)).isEqualTo((short) 0xABCD);
    }

    @Test
    void set_i8_narrowsToLowByte() {
        // Given
        MemorySegment sut = buffer();

        // When
        PTypeIO.set(sut, 0, PType.I8, 0xFF00L | 0x7AL);

        // Then
        assertThat(sut.get(JAVA_BYTE, 0)).isEqualTo((byte) 0x7A);
    }

    @Test
    void set_f16_writesLow16BitsAsShort() {
        // Given — F16 is carried as raw 16-bit short bits (0x3C00 == half-precision 1.0)
        MemorySegment sut = buffer();

        // When
        PTypeIO.set(sut, 0, PType.F16, 0x3C00L);

        // Then
        assertThat(sut.get(LE_SHORT, 0)).isEqualTo((short) 0x3C00);
    }

    // ── set: float bit-reinterpretation ──────────────────────────────────────────

    @Test
    void set_f32_reinterpretsIntBitsAsFloat() {
        // Given — bits carry the IEEE-754 encoding of the float, not a numeric value to narrow
        MemorySegment sut = buffer();
        float value = 3.5f;

        // When
        PTypeIO.set(sut, 0, PType.F32, Float.floatToIntBits(value) & 0xFFFF_FFFFL);

        // Then
        assertThat(sut.get(LE_FLOAT, 0)).isEqualTo(value);
    }

    @Test
    void set_f64_reinterpretsLongBitsAsDouble() {
        // Given
        MemorySegment sut = buffer();
        double value = -123.456;

        // When
        PTypeIO.set(sut, 0, PType.F64, Double.doubleToLongBits(value));

        // Then
        assertThat(sut.get(LE_DOUBLE, 0)).isEqualTo(value);
    }

    // ── copyArray: size + little-endian round-trip per carrier ───────────────────

    @Test
    void copyArray_longs_allocatesExactSizeAndRoundTrips() {
        // Given
        long[] data = {1L, -2L, 0x0123_4567_89AB_CDEFL};

        // When
        MemorySegment result = PTypeIO.copyArray(PType.I64, data, data.length);

        // Then — exactly count * 8 bytes, each element little-endian
        assertThat(result.byteSize()).isEqualTo(data.length * 8L);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.getAtIndex(LE_LONG, i)).isEqualTo(data[i]);
        }
    }

    @Test
    void copyArray_ints_allocatesExactSizeAndRoundTrips() {
        // Given
        int[] data = {7, -8, 0x1234_5678};

        // When
        MemorySegment result = PTypeIO.copyArray(PType.I32, data, data.length);

        // Then
        assertThat(result.byteSize()).isEqualTo(data.length * 4L);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.getAtIndex(LE_INT, i)).isEqualTo(data[i]);
        }
    }

    @Test
    void copyArray_shorts_allocatesExactSizeAndRoundTrips() {
        // Given
        short[] data = {9, -10, (short) 0xABCD};

        // When
        MemorySegment result = PTypeIO.copyArray(PType.I16, data, data.length);

        // Then
        assertThat(result.byteSize()).isEqualTo(data.length * 2L);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.getAtIndex(LE_SHORT, i)).isEqualTo(data[i]);
        }
    }

    @Test
    void copyArray_bytes_allocatesExactSizeAndRoundTrips() {
        // Given
        byte[] data = {11, -12, (byte) 0xFE};

        // When
        MemorySegment result = PTypeIO.copyArray(PType.I8, data, data.length);

        // Then
        assertThat(result.byteSize()).isEqualTo((long) data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.get(JAVA_BYTE, i)).isEqualTo(data[i]);
        }
    }

    @Test
    void copyArray_floats_allocatesExactSizeAndRoundTrips() {
        // Given
        float[] data = {1.5f, -2.25f, 0f};

        // When
        MemorySegment result = PTypeIO.copyArray(PType.F32, data, data.length);

        // Then
        assertThat(result.byteSize()).isEqualTo(data.length * 4L);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.getAtIndex(LE_FLOAT, i)).isEqualTo(data[i]);
        }
    }

    @Test
    void copyArray_doubles_allocatesExactSizeAndRoundTrips() {
        // Given
        double[] data = {3.5, -4.75, 0.0};

        // When
        MemorySegment result = PTypeIO.copyArray(PType.F64, data, data.length);

        // Then
        assertThat(result.byteSize()).isEqualTo(data.length * 8L);
        for (int i = 0; i < data.length; i++) {
            assertThat(result.getAtIndex(LE_DOUBLE, i)).isEqualTo(data[i]);
        }
    }

    @Test
    void copyArray_unsupportedArrayType_throws() {
        // Given — a boxed array PTypeIO does not handle

        // When / Then
        assertThatThrownBy(() -> PTypeIO.copyArray(PType.I32, new String[]{"x"}, 1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("unsupported array type");
    }
}
