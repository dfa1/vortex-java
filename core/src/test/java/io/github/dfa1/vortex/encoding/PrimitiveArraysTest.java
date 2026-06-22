package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrimitiveArraysTest {

    @Test
    void toLongs_i8_signExtends() {
        // Given a byte array with a negative value
        byte[] data = {0, 1, -1, Byte.MIN_VALUE, Byte.MAX_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.I8, EncodingId.FASTLANES_DELTA);

        // Then negatives sign-extend to 64 bits
        assertThat(result).containsExactly(0L, 1L, -1L, -128L, 127L);
    }

    @Test
    void toLongs_u8_zeroExtends() {
        // Given a byte array whose high bit is set (would be negative if signed)
        byte[] data = {0, 1, -1, Byte.MIN_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.U8, EncodingId.FASTLANES_DELTA);

        // Then the raw byte is zero-extended into 0..255
        assertThat(result).containsExactly(0L, 1L, 255L, 128L);
    }

    @Test
    void toLongs_i16_signExtends() {
        // Given
        short[] data = {0, -1, Short.MIN_VALUE, Short.MAX_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.I16, EncodingId.FASTLANES_DELTA);

        // Then
        assertThat(result).containsExactly(0L, -1L, -32768L, 32767L);
    }

    @Test
    void toLongs_u16_zeroExtends() {
        // Given a value with the high bit set
        short[] data = {-1, Short.MIN_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.U16, EncodingId.FASTLANES_DELTA);

        // Then zero-extended into 0..65535
        assertThat(result).containsExactly(65535L, 32768L);
    }

    @Test
    void toLongs_i32_signExtends() {
        // Given
        int[] data = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.I32, EncodingId.FASTLANES_DELTA);

        // Then
        assertThat(result).containsExactly(0L, -1L, (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE);
    }

    @Test
    void toLongs_u32_zeroExtends() {
        // Given a value with the high bit set
        int[] data = {-1, Integer.MIN_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.U32, EncodingId.FASTLANES_DELTA);

        // Then zero-extended into 0..2^32-1
        assertThat(result).containsExactly(0xFFFF_FFFFL, 0x8000_0000L);
    }

    @Test
    void toLongs_i64_returnsSameArrayNoCopy() {
        // Given a long array
        long[] data = {1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};

        // When
        long[] result = PrimitiveArrays.toLongs(data, PType.I64, EncodingId.FASTLANES_DELTA);

        // Then the I64/U64 path is a passthrough — no copy
        assertThat(result).isSameAs(data);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void toLongs_floatingPtypes_throwWithSuppliedEncodingId(PType ptype) {
        // Given floating ptypes are not integer-widen targets; When/Then it throws, attributed to
        // the caller's encoding id (here FrameOfReference) rather than a hardcoded one
        assertThatThrownBy(() -> PrimitiveArrays.toLongs(new float[1], ptype, EncodingId.FASTLANES_FOR))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unsupported ptype: " + ptype);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void fromLongs_roundTripsThroughToLongs(PType ptype) {
        // Given values that exercise the low bytes at each width
        long[] original = {0L, 1L, 2L, 7L, 42L};

        try (Arena arena = Arena.ofConfined()) {
            // When written to a segment and read back at the ptype's width
            MemorySegment seg = PrimitiveArrays.fromLongs(original, ptype, arena);

            // Then the segment has one element per value at the expected width...
            assertThat(seg.byteSize()).isEqualTo((long) original.length * ptype.byteSize());
            // ...and each element round-trips (values are small + positive, so width-narrowing is lossless)
            for (int i = 0; i < original.length; i++) {
                assertThat(readElement(seg, ptype, i)).isEqualTo(original[i]);
            }
        }
    }

    @Test
    void fromLongs_i64_writesLittleEndian() {
        // Given a single value with distinct bytes
        long[] original = {0x0102_0304_0506_0708L};

        try (Arena arena = Arena.ofConfined()) {
            // When written via the bulk I64 path
            MemorySegment seg = PrimitiveArrays.fromLongs(original, PType.I64, arena);

            // Then it is stored little-endian (lowest byte first)
            assertThat(seg.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0x08);
            assertThat(seg.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(0x0102_0304_0506_0708L);
        }
    }

    @Test
    void fromLongs_narrowWidth_keepsOnlyLowBytes() {
        // Given a value whose high bytes exceed the target width
        long[] original = {0x1234_5678L};

        try (Arena arena = Arena.ofConfined()) {
            // When narrowed to I8 (1 byte/elem)
            MemorySegment seg = PrimitiveArrays.fromLongs(original, PType.I8, arena);

            // Then only the low byte survives
            assertThat(seg.byteSize()).isEqualTo(1L);
            assertThat(seg.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0x78);
        }
    }

    private static long readElement(MemorySegment seg, PType ptype, int i) {
        return switch (ptype) {
            case I8, U8 -> seg.get(ValueLayout.JAVA_BYTE, i);
            case I16, U16 -> seg.getAtIndex(PTypeIO.LE_SHORT, i);
            case I32, U32 -> seg.getAtIndex(PTypeIO.LE_INT, i);
            case I64, U64 -> seg.getAtIndex(PTypeIO.LE_LONG, i);
            default -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
        };
    }
}
