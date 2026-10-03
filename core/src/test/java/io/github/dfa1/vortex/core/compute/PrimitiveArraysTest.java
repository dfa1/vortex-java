package io.github.dfa1.vortex.core.compute;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;

import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
            assertThat(seg.getAtIndex(VortexFormat.LE_LONG, 0)).isEqualTo(0x0102_0304_0506_0708L);
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

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "U64"})
    void requireUnsigned_unsignedPtype_doesNotThrow(PType ptype) {
        // Given/When/Then
        assertThatCode(() -> PrimitiveArrays.requireUnsigned(ptype, EncodingId.VORTEX_RUNEND))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "I64", "F16", "F32", "F64"})
    void requireUnsigned_notUnsignedPtype_throws(PType ptype) {
        // Given/When/Then a signed or floating ptype is rejected, attributed to the caller's encoding
        assertThatThrownBy(() -> PrimitiveArrays.requireUnsigned(ptype, EncodingId.VORTEX_RUNEND))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("vortex.runend")
                .hasMessageContaining("expected an unsigned ptype, got " + ptype);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void readLong_roundTripsThroughFromLongs(PType ptype) {
        // Given a segment written via fromLongs (already covers little-endian width per ptype)
        long[] original = {0L, 1L, 42L};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = PrimitiveArrays.fromLongs(original, ptype, arena);

            // When reading each element back at its byte offset
            for (int i = 0; i < original.length; i++) {
                long result = PrimitiveArrays.readLong(seg, (long) i * ptype.byteSize(), ptype, EncodingId.FASTLANES_DELTA);

                // Then
                assertThat(result).isEqualTo(original[i]);
            }
        }
    }

    @Test
    void readLong_u8_zeroExtendsHighBit() {
        // Given a byte whose high bit is set
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(1);
            seg.set(ValueLayout.JAVA_BYTE, 0, (byte) -1);

            // When
            long result = PrimitiveArrays.readLong(seg, 0, PType.U8, EncodingId.FASTLANES_DELTA);

            // Then zero-extended, not sign-extended
            assertThat(result).isEqualTo(255L);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void readLong_floatingPtypes_throw(PType ptype) {
        // Given/When/Then
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            assertThatThrownBy(() -> PrimitiveArrays.readLong(seg, 0, ptype, EncodingId.FASTLANES_DELTA))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported ptype: " + ptype);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void toLongs_segment_readsContiguousElementsFromOffset(PType ptype) {
        // Given a segment holding five elements, only elements [2, 5) requested
        long[] all = {0L, 1L, 2L, 3L, 4L};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = PrimitiveArrays.fromLongs(all, ptype, arena);

            // When
            long[] result = PrimitiveArrays.toLongs(seg, 2, 3, ptype, EncodingId.FASTLANES_DELTA);

            // Then
            assertThat(result).containsExactly(2L, 3L, 4L);
        }
    }

    @Test
    void toLongsInto_writesIntoCallerSuppliedArrayWithoutAllocatingANewOne() {
        // Given a pre-sized scratch array a hot per-chunk loop reuses across calls
        long[] scratch = new long[3];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = PrimitiveArrays.fromLongs(new long[]{5L, 6L, 7L}, PType.I32, arena);

            // When
            PrimitiveArrays.toLongsInto(seg, 0, 3, PType.I32, EncodingId.FASTLANES_DELTA, scratch);

            // Then the caller's array is filled in place
            assertThat(scratch).containsExactly(5L, 6L, 7L);
        }
    }

    private static long readElement(MemorySegment seg, PType ptype, int i) {
        return switch (ptype) {
            case I8, U8 -> seg.get(ValueLayout.JAVA_BYTE, i);
            case I16, U16 -> seg.getAtIndex(VortexFormat.LE_SHORT, i);
            case I32, U32 -> seg.getAtIndex(VortexFormat.LE_INT, i);
            case I64, U64 -> seg.getAtIndex(VortexFormat.LE_LONG, i);
            default -> throw new IllegalArgumentException("not an integer ptype: " + ptype);
        };
    }

    @Test
    void compact_i8_keepsOnlyValidElements() {
        // Given a leading and a trailing invalid slot around real values
        byte[] data = {9, 10, 20, 9};
        boolean[] mask = {false, true, true, false};

        // When
        byte[] result = (byte[]) PrimitiveArrays.compact(PType.I8, data, mask);

        // Then
        assertThat(result).containsExactly((byte) 10, (byte) 20);
    }

    @Test
    void compact_i16_keepsOnlyValidElements() {
        // Given
        short[] data = {9, 10, 20, 9};
        boolean[] mask = {false, true, true, false};

        // When
        short[] result = (short[]) PrimitiveArrays.compact(PType.I16, data, mask);

        // Then
        assertThat(result).containsExactly((short) 10, (short) 20);
    }

    @Test
    void compact_f16_keepsOnlyValidElements() {
        // Given — F16 shares I16/U16's short[] storage shape
        short[] data = {9, 10, 20, 9};
        boolean[] mask = {false, true, true, false};

        // When
        short[] result = (short[]) PrimitiveArrays.compact(PType.F16, data, mask);

        // Then
        assertThat(result).containsExactly((short) 10, (short) 20);
    }

    @Test
    void compact_i32_keepsOnlyValidElements() {
        // Given
        int[] data = {9, 10, 20, 9};
        boolean[] mask = {false, true, true, false};

        // When
        int[] result = (int[]) PrimitiveArrays.compact(PType.I32, data, mask);

        // Then
        assertThat(result).containsExactly(10, 20);
    }

    @Test
    void compact_i64_keepsOnlyValidElements() {
        // Given
        long[] data = {9L, 10L, 20L, 9L};
        boolean[] mask = {false, true, true, false};

        // When
        long[] result = (long[]) PrimitiveArrays.compact(PType.I64, data, mask);

        // Then
        assertThat(result).containsExactly(10L, 20L);
    }

    @Test
    void compact_f32_keepsOnlyValidElements() {
        // Given
        float[] data = {9f, 10f, 20f, 9f};
        boolean[] mask = {false, true, true, false};

        // When
        float[] result = (float[]) PrimitiveArrays.compact(PType.F32, data, mask);

        // Then
        assertThat(result).containsExactly(10f, 20f);
    }

    @Test
    void compact_f64_keepsOnlyValidElements() {
        // Given
        double[] data = {9.0, 10.0, 20.0, 9.0};
        boolean[] mask = {false, true, true, false};

        // When
        double[] result = (double[]) PrimitiveArrays.compact(PType.F64, data, mask);

        // Then
        assertThat(result).containsExactly(10.0, 20.0);
    }

    @Test
    void compact_allValid_returnsEveryElement() {
        // Given no placeholder slots at all
        long[] data = {1L, 2L, 3L};
        boolean[] mask = {true, true, true};

        // When
        long[] result = (long[]) PrimitiveArrays.compact(PType.I64, data, mask);

        // Then
        assertThat(result).containsExactly(1L, 2L, 3L);
    }

    @Test
    void compact_allInvalid_returnsEmptyArray() {
        // Given — no real value exists anywhere, e.g. an all-null zone/chunk
        long[] data = {0L, 0L, 0L};
        boolean[] mask = {false, false, false};

        // When
        long[] result = (long[]) PrimitiveArrays.compact(PType.I64, data, mask);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void compact_empty_returnsEmptyArray() {
        // Given no elements at all
        // When
        long[] result = (long[]) PrimitiveArrays.compact(PType.I64, new long[0], new boolean[0]);

        // Then
        assertThat(result).isEmpty();
    }

    /// `fromLongsArray` is the inverse of `toLongs`: every integer type's extremes (the signed
    /// minimum and the unsigned all-ones pattern) must survive widen-then-narrow bit for bit.
    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void fromLongsArray_invertsToLongs(PType ptype) {
        // Given — the minimum, -1/all-ones, zero and maximum at this width
        long[] wide = PrimitiveArrays.toLongs(switch (ptype) {
            case I8, U8 -> new byte[]{Byte.MIN_VALUE, -1, 0, Byte.MAX_VALUE};
            case I16, U16 -> new short[]{Short.MIN_VALUE, -1, 0, Short.MAX_VALUE};
            case I32, U32 -> new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE};
            default -> new long[]{Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE};
        }, ptype, EncodingId.VORTEX_PRIMITIVE);

        // When
        Object result = PrimitiveArrays.fromLongsArray(wide, ptype, EncodingId.VORTEX_PRIMITIVE);

        // Then
        assertThat(PrimitiveArrays.toLongs(result, ptype, EncodingId.VORTEX_PRIMITIVE)).containsExactly(wide);
    }

    @Test
    void fromLongsArray_rejectsFloatingPoint() {
        // When / Then
        assertThatThrownBy(() -> PrimitiveArrays.fromLongsArray(new long[]{1L}, PType.F64, EncodingId.VORTEX_PRIMITIVE))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unsupported ptype: F64");
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void fromIntsArray_matchesFromLongsArray(PType ptype) {
        // Given — codes/offsets within every width's unsigned range, including each width's top bit
        int[] ints = {0, 1, 127, 255};

        // When
        Object result = PrimitiveArrays.fromIntsArray(ints, ptype, EncodingId.VORTEX_PRIMITIVE);

        // Then — same carrier and values as the long[] path the encoders used before
        long[] wide = {0, 1, 127, 255};
        assertThat(result).isInstanceOf(PrimitiveArrays.fromLongsArray(wide, ptype, EncodingId.VORTEX_PRIMITIVE).getClass());
        assertThat(PrimitiveArrays.toLongs(result, ptype, EncodingId.VORTEX_PRIMITIVE))
                .containsExactly(PrimitiveArrays.toLongs(PrimitiveArrays.fromLongsArray(wide, ptype, EncodingId.VORTEX_PRIMITIVE),
                        ptype, EncodingId.VORTEX_PRIMITIVE));
    }

    @Test
    void fromIntsArray_i32_returnsTheInputItself() {
        // Given
        int[] ints = {1, 2, 3};

        // When
        Object result = PrimitiveArrays.fromIntsArray(ints, PType.U32, EncodingId.VORTEX_PRIMITIVE);

        // Then — no copy at the int carrier's own width
        assertThat(result).isSameAs(ints);
    }

    @Test
    void fromIntsArray_rejectsFloatingPoint() {
        // When / Then
        assertThatThrownBy(() -> PrimitiveArrays.fromIntsArray(new int[]{1}, PType.F32, EncodingId.VORTEX_PRIMITIVE))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("unsupported ptype: F32");
    }

    @Test
    void fromBitsArray_floats_roundTripNegativeZeroAndNanPayloads() {
        // Given — values whose == comparison would lose them: -0.0 and a NaN with a payload
        double nanWithPayload = Double.longBitsToDouble(0x7ff8_0000_0000_1234L);
        long[] doubleBits = {Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(nanWithPayload)};
        long[] floatBits = {Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(Float.intBitsToFloat(0x7fc0_0042))};

        // When
        double[] doubles = (double[]) PrimitiveArrays.fromBitsArray(doubleBits, PType.F64, EncodingId.VORTEX_PRIMITIVE);
        float[] floats = (float[]) PrimitiveArrays.fromBitsArray(floatBits, PType.F32, EncodingId.VORTEX_PRIMITIVE);

        // Then — bit-exact
        assertThat(Double.doubleToRawLongBits(doubles[0])).isEqualTo(doubleBits[0]);
        assertThat(Double.doubleToRawLongBits(doubles[1])).isEqualTo(doubleBits[1]);
        assertThat(Float.floatToRawIntBits(floats[0])).isEqualTo((int) floatBits[0]);
        assertThat(Float.floatToRawIntBits(floats[1])).isEqualTo((int) floatBits[1]);
    }

    @Test
    void fromBitsArray_f16_keepsTheRawBitsInAShortArray() {
        // Given — F16 has no Java type; its 16 bits travel in a short[]
        long[] bits = {0x3c00, 0xfbff};

        // When
        Object result = PrimitiveArrays.fromBitsArray(bits, PType.F16, EncodingId.VORTEX_PRIMITIVE);

        // Then
        assertThat(result).isInstanceOf(short[].class);
        assertThat((short[]) result).containsExactly((short) 0x3c00, (short) 0xfbff);
    }

    @Test
    void fromBitsArray_integers_delegateToFromLongsArray() {
        // Given
        long[] bits = {-1, 0, 7};

        // When
        Object result = PrimitiveArrays.fromBitsArray(bits, PType.I16, EncodingId.VORTEX_PRIMITIVE);

        // Then
        assertThat((short[]) result).containsExactly((short) -1, (short) 0, (short) 7);
    }
    @ParameterizedTest
    @EnumSource(PType.class)
    void toSegment_writesLittleEndianElementAlignedBytes(PType ptype) {
        // Given — -2 sets every byte of its width, so a missing byte swap or a short copy shows up;
        // fromBitsArray builds the right carrier type for every ptype, floats included.
        long[] bits = {1, -2, 0x7f};
        Object carrier = PrimitiveArrays.fromBitsArray(bits, ptype, EncodingId.VORTEX_PRIMITIVE);
        int width = ptype.byteSize();

        try (Arena arena = Arena.ofConfined()) {
            // When
            MemorySegment result = PrimitiveArrays.toSegment(carrier, ptype, arena);

            // Then — Rust rejects a buffer below its element alignment, so the address is asserted too
            assertThat(result.byteSize()).isEqualTo(3L * width);
            assertThat(result.address() % width).isZero();
            long mask = width == 8 ? -1L : (1L << (width * 8)) - 1;
            for (int i = 0; i < bits.length; i++) {
                assertThat(leBits(result, (long) i * width, width)).isEqualTo(bits[i] & mask);
            }
        }
    }

    private static long leBits(MemorySegment seg, long offset, int width) {
        long bits = 0;
        for (int k = 0; k < width; k++) {
            bits |= (seg.get(ValueLayout.JAVA_BYTE, offset + k) & 0xFFL) << (8 * k);
        }
        return bits;
    }
}
