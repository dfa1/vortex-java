package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the typed `OffsetXxxArray` slice views. Each slice shifts every
/// access by a fixed offset into an inner array; these cover scalar reads, the
/// unsigned-widening `getInt` path, and fold reduction over the sliced range.
class OffsetArrayTest {

    private static final DType U8 = DType.U8;
    private static final DType U16 = DType.U16;
    private static final DType F32 = DType.F32;

    @Nested
    class Byte {

        @Test
        void getByteShiftsByOffset() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                ByteArray inner = byteArray(arena, (byte) 10, (byte) 20, (byte) 30, (byte) 40);
                var sut = new OffsetByteArray(U8, 2, inner, 2L);

                // When / Then
                assertThat(sut.getByte(0)).isEqualTo((byte) 30);
                assertThat(sut.getByte(1)).isEqualTo((byte) 40);
            }
        }

        @Test
        void getIntWidensUnsignedThroughOffset() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — 0xFF at inner index 1; sliced index 0 must read it as unsigned 255
                ByteArray inner = byteArray(arena, (byte) 1, (byte) 0xFF);
                var sut = new OffsetByteArray(U8, 1, inner, 1L);

                // When / Then
                assertThat(sut.getInt(0)).isEqualTo(255);
            }
        }

        @Test
        void foldReducesSlicedRange() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                ByteArray inner = byteArray(arena, (byte) 1, (byte) 2, (byte) 3, (byte) 4);
                var sut = new OffsetByteArray(U8, 2, inner, 1L);

                // When
                long result = sut.fold(0L, java.lang.Long::sum);

                // Then — bytes at sliced [0,1] = inner[1], inner[2] = 2 + 3 = 5
                assertThat(result).isEqualTo(5L);
            }
        }
    }

    @Nested
    class Short {

        @Test
        void getShortShiftsByOffset() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                ShortArray inner = shortArray(arena, (short) 100, (short) 200, (short) 300);
                var sut = new OffsetShortArray(U16, 2, inner, 1L);

                // When / Then
                assertThat(sut.getShort(0)).isEqualTo((short) 200);
                assertThat(sut.getShort(1)).isEqualTo((short) 300);
            }
        }

        @Test
        void getIntWidensUnsignedThroughOffset() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — 0xFFFF at inner index 1; sliced index 0 reads unsigned 65535
                ShortArray inner = shortArray(arena, (short) 1, (short) 0xFFFF);
                var sut = new OffsetShortArray(U16, 1, inner, 1L);

                // When / Then
                assertThat(sut.getInt(0)).isEqualTo(65535);
            }
        }

        @Test
        void foldReducesSlicedRange() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                ShortArray inner = shortArray(arena, (short) 5, (short) 6, (short) 7);
                var sut = new OffsetShortArray(U16, 2, inner, 1L);

                // When
                long result = sut.fold(0L, java.lang.Long::sum);

                // Then — shorts at sliced [0,1] = inner[1], inner[2] = 6 + 7 = 13
                assertThat(result).isEqualTo(13L);
            }
        }
    }

    @Nested
    class Float {

        @Test
        void getFloatShiftsByOffset() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                FloatArray inner = floatArray(arena, 1.5f, 2.5f, 3.5f, 4.5f);
                var sut = new OffsetFloatArray(F32, 2, inner, 2L);

                // When / Then
                assertThat(sut.getFloat(0)).isEqualTo(3.5f);
                assertThat(sut.getFloat(1)).isEqualTo(4.5f);
            }
        }

        @Test
        void foldReducesSlicedRange() {
            try (Arena arena = Arena.ofConfined()) {
                // Given
                FloatArray inner = floatArray(arena, 1.0f, 2.0f, 3.0f);
                var sut = new OffsetFloatArray(F32, 2, inner, 1L);

                // When
                double result = sut.fold(0.0, java.lang.Double::sum);

                // Then — floats at sliced [0,1] = inner[1], inner[2] = 2 + 3 = 5
                assertThat(result).isEqualTo(5.0);
            }
        }
    }

    @Nested
    class Bool {
        @Test
        void getBooleanShiftsByOffset() {
            // Given — bits [F, T, T, F]
            BoolArray inner = TestArrays.bools(false, true, true, false);
            var sut = new OffsetBoolArray(DType.BOOL, 2, inner, 1L);

            // When / Then
            assertThat(sut.getBoolean(0)).isTrue();  // inner[1]
            assertThat(sut.getBoolean(1)).isTrue();  // inner[2]
            assertThat(sut.length()).isEqualTo(2);
        }

        // Seeded windows over a flat bitmap, at every bit offset and length mod 8: the shifted
        // byte copy must match the per-row getBoolean reference exactly, including the bits past
        // length() in the last byte, which a consumer reading whole bytes would otherwise see
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        void materialize_flatInner_matchesPerRowAtEveryBitOffset(int seed) {
            // Given
            Random random = new Random(seed);
            boolean[] values = new boolean[40];
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextBoolean();
            }
            BoolArray inner = TestArrays.bools(values);
            long offset = random.nextInt(17);
            long length = random.nextInt((int) (values.length - offset) + 1);
            var sut = new OffsetBoolArray(DType.BOOL, length, inner, offset);
            // the same window through a non-flat inner takes the forEachBoolean default
            var reference = new OffsetBoolArray(DType.BOOL, length, new OffsetBoolArray(DType.BOOL, values.length, inner, 0), offset);

            // When
            MemorySegment result = sut.materialize(Arena.ofAuto());

            // Then
            assertThat(result.mismatch(reference.materialize(Arena.ofAuto()))).isEqualTo(-1L);
            for (int i = 0; i < length; i++) {
                boolean bit = (result.get(ValueLayout.JAVA_BYTE, i >>> 3) & (1 << (i & 7))) != 0;
                assertThat(bit).as("row %d", i).isEqualTo(values[(int) offset + i]);
            }
            if (length % 8 != 0) {
                int last = result.get(ValueLayout.JAVA_BYTE, length >>> 3) & 0xFF;
                assertThat(last >>> (length % 8)).isZero();
            }
        }
    }

    @Nested
    class FlatWindow {

        // A scan window's slice of a canonicalized shared flat: materialize must return exactly the
        // window, as a view of the flat buffer rather than a row-by-row copy through getX.

        @Test
        void materialize_flatInner_returnsZeroCopyWindow() {
            // Given
            LongArray inner = TestArrays.longs(5L, 6L, 7L, 8L);
            var sut = new OffsetLongArray(DType.I64, 2, inner, 1L);

            // When
            MemorySegment result = sut.materialize(Arena.ofAuto());

            // Then
            assertThat(result.byteSize()).isEqualTo(16L);
            assertThat(result.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, 0)).isEqualTo(6L);
            assertThat(result.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, 1)).isEqualTo(7L);
            assertThat(result.isReadOnly()).isTrue();
            assertThat(inner.materialize(Arena.ofAuto()).asSlice(8, 16).address()).isEqualTo(result.address());
        }

        @Test
        void materialize_broadcastInner_fallsBackToPerRowCopy() {
            // Given a one-element broadcast buffer standing for 4 rows: it does not cover the
            // window, so a zero-copy slice would read past it; the per-row path broadcasts instead
            MemorySegment one = Arena.ofAuto().allocate(4, 4);
            one.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 42);
            IntArray inner = new MaterializedIntArray(DType.I32, 4, one);
            var sut = new OffsetIntArray(DType.I32, 2, inner, 2L);

            // When
            MemorySegment result = sut.materialize(Arena.ofAuto());

            // Then
            assertThat(result.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0)).isEqualTo(42);
            assertThat(result.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1)).isEqualTo(42);
        }
    }

    @Nested
    class Int {
        @Test
        void getIntShiftsByOffset() {
            // Given
            IntArray inner = TestArrays.ints(10, 20, 30, 40);
            var sut = new OffsetIntArray(DType.I32, 2, inner, 2L);

            // When / Then
            assertThat(sut.getInt(0)).isEqualTo(30);
            assertThat(sut.getInt(1)).isEqualTo(40);
        }
    }

    @Nested
    class Long {
        @Test
        void getLongShiftsByOffset() {
            // Given
            LongArray inner = TestArrays.longs(5L, 6L, 7L, 8L);
            var sut = new OffsetLongArray(DType.I64, 2, inner, 1L);

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(6L);
            assertThat(sut.getLong(1)).isEqualTo(7L);
        }
    }

    @Nested
    class Double {
        @Test
        void getDoubleShiftsByOffset() {
            // Given
            DoubleArray inner = TestArrays.doubles(1.5, 2.5, 3.5);
            var sut = new OffsetDoubleArray(DType.F64, 2, inner, 1L);

            // When / Then
            assertThat(sut.getDouble(0)).isEqualTo(2.5);
            assertThat(sut.getDouble(1)).isEqualTo(3.5);
        }
    }

    private static ByteArray byteArray(Arena arena, byte... vs) {
        MemorySegment seg = arena.allocate(vs.length, 1);
        for (int i = 0; i < vs.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, vs[i]);
        }
        return new MaterializedByteArray(U8, vs.length, seg.asReadOnly());
    }

    private static ShortArray shortArray(Arena arena, short... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, vs[i]);
        }
        return new MaterializedShortArray(U16, vs.length, seg.asReadOnly());
    }

    private static FloatArray floatArray(Arena arena, float... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, vs[i]);
        }
        return new MaterializedFloatArray(F32, vs.length, seg.asReadOnly());
    }
}
