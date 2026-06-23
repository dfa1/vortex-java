package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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
