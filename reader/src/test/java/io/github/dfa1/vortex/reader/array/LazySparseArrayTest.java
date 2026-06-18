package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy Sparse records. Covers fill vs patch dispatch, ordered
/// forEach iteration, fold reduction, and offset slicing semantics.
class LazySparseArrayTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType I32 = new DType.Primitive(PType.I32, false);
    private static final DType F64 = new DType.Primitive(PType.F64, false);
    private static final DType F32 = new DType.Primitive(PType.F32, false);
    private static final DType I8 = new DType.Primitive(PType.I8, false);
    private static final DType U8 = new DType.Primitive(PType.U8, false);
    private static final DType I16 = new DType.Primitive(PType.I16, false);
    private static final DType U16 = new DType.Primitive(PType.U16, false);

    @Nested
    class Long {

        @Test
        void unpatchedPositionsReturnFill() {
            try (Arena arena = Arena.ofConfined()) {
                // length=5, fill=99, patches at index 1 → 7, index 3 → 11
                LongArray values = longArray(arena, 7L, 11L);
                Array indices = intArray(arena, 1, 3);
                var sut = new LazySparseLongArray(I64, 5, 99L, values, indices, 0L);

                assertThat(sut.getLong(0)).isEqualTo(99L);
                assertThat(sut.getLong(1)).isEqualTo(7L);
                assertThat(sut.getLong(2)).isEqualTo(99L);
                assertThat(sut.getLong(3)).isEqualTo(11L);
                assertThat(sut.getLong(4)).isEqualTo(99L);
            }
        }

        @Test
        void forEachEmitsInOrder() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 7L, 11L);
                Array indices = intArray(arena, 1, 3);
                var sut = new LazySparseLongArray(I64, 5, 99L, values, indices, 0L);

                var seen = new ArrayList<java.lang.Long>();
                sut.forEachLong(seen::add);

                assertThat(seen).containsExactly(99L, 7L, 99L, 11L, 99L);
            }
        }

        @Test
        void foldSumsFillAndPatches() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 7L, 11L);
                Array indices = intArray(arena, 1, 3);
                var sut = new LazySparseLongArray(I64, 5, 99L, values, indices, 0L);

                long sum = sut.fold(0L, java.lang.Long::sum);

                // 99 + 7 + 99 + 11 + 99 = 315
                assertThat(sum).isEqualTo(315L);
            }
        }

        @Test
        void offsetSkipsLeadingPatches() {
            try (Arena arena = Arena.ofConfined()) {
                // length=3 covering abs [4..7), fill=1, patches at abs 4 and 6
                LongArray values = longArray(arena, 10L, 11L, 12L);
                Array indices = intArray(arena, 1, 4, 6);
                var sut = new LazySparseLongArray(I64, 3, 1L, values, indices, 4L);

                assertThat(sut.getLong(0)).isEqualTo(11L);
                assertThat(sut.getLong(1)).isEqualTo(1L);
                assertThat(sut.getLong(2)).isEqualTo(12L);
            }
        }

        @Test
        void noPatchesIsAllFill() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena);
                Array indices = intArray(arena);
                var sut = new LazySparseLongArray(I64, 3, 42L, values, indices, 0L);

                var seen = new ArrayList<java.lang.Long>();
                sut.forEachLong(seen::add);

                assertThat(seen).containsExactly(42L, 42L, 42L);
            }
        }
    }

    @Nested
    class IntAndDouble {

        @Test
        void intPatchDispatches() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, 100, 200);
                Array indices = intArray(arena, 0, 2);
                var sut = new LazySparseIntArray(I32, 3, 5, values, indices, 0L);

                assertThat(sut.getInt(0)).isEqualTo(100);
                assertThat(sut.getInt(1)).isEqualTo(5);
                assertThat(sut.getInt(2)).isEqualTo(200);
            }
        }

        @Test
        void doublePatchDispatches() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray values = doubleArray(arena, 1.5, 2.5);
                Array indices = intArray(arena, 0, 2);
                var sut = new LazySparseDoubleArray(F64, 3, 0.0, values, indices, 0L);

                assertThat(sut.getDouble(0)).isEqualTo(1.5);
                assertThat(sut.getDouble(1)).isEqualTo(0.0);
                assertThat(sut.getDouble(2)).isEqualTo(2.5);
            }
        }
    }

    @Nested
    class ByteAndShort {

        // These exercise SparseArrays.patchedInt / foldInt (the shared int path the
        // byte/short sparse records delegate to) — distinct from the long/int/double
        // records above which fold over their own typed accessor.

        @Test
        void bytePatchAndFillDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — patches at 0->7 and 2->11, fill 5
                ByteArray values = byteArray(arena, (byte) 7, (byte) 11);
                Array indices = intArray(arena, 0, 2);
                var sut = new LazySparseByteArray(I8, 3, (byte) 5, 5, values, indices, 0L);

                // When / Then
                assertThat(sut.getByte(0)).isEqualTo((byte) 7);
                assertThat(sut.getInt(1)).isEqualTo(5);
                assertThat(sut.getInt(2)).isEqualTo(11);
            }
        }

        @Test
        void byteGetIntWidensUnsignedFill() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — U8 fill 0xFF -> fillInt 255
                ByteArray values = byteArray(arena, (byte) 1);
                Array indices = intArray(arena, 0);
                var sut = new LazySparseByteArray(U8, 3, (byte) 0xFF, 255, values, indices, 0L);

                // When / Then — unpatched position reports 255 not -1
                assertThat(sut.getInt(1)).isEqualTo(255);
            }
        }

        @Test
        void byteFoldSumsThroughIntPath() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — length 5, fill 10, patches 1->7 and 3->11
                ByteArray values = byteArray(arena, (byte) 7, (byte) 11);
                Array indices = intArray(arena, 1, 3);
                var sut = new LazySparseByteArray(I8, 5, (byte) 10, 10, values, indices, 0L);

                // When / Then — 10+7+10+11+10 = 48
                assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(48L);
            }
        }

        @Test
        void byteNullPatchesIsAllFill() {
            // Given — no patches
            var sut = new LazySparseByteArray(I8, 3, (byte) 9, 9, null, null, 0L);

            // When / Then — every position is the fill
            assertThat(sut.getInt(2)).isEqualTo(9);
            assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(27L);
        }

        @Test
        void shortPatchAndFoldDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — length 5, fill 1, patches 1->100 and 3->200
                ShortArray values = shortArray(arena, (short) 100, (short) 200);
                Array indices = intArray(arena, 1, 3);
                var sut = new LazySparseShortArray(I16, 5, (short) 1, 1, values, indices, 0L);

                // When / Then — fill + patches; fold 1+100+1+200+1 = 303
                assertThat(sut.getInt(0)).isEqualTo(1);
                assertThat(sut.getInt(1)).isEqualTo(100);
                assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(303L);
            }
        }

        @Test
        void shortGetIntWidensUnsigned() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — widening flows through patchValues.getInt, so the patch array must be U16
                MemorySegment seg = arena.allocate(2L, 2);
                seg.setAtIndex(ValueLayout.JAVA_SHORT, 0, (short) 0xFFFF);
                ShortArray values = new MaterializedShortArray(U16, 1, seg.asReadOnly());
                Array indices = intArray(arena, 0);
                var sut = new LazySparseShortArray(U16, 2, (short) 0, 0, values, indices, 0L);

                // When / Then
                assertThat(sut.getInt(0)).isEqualTo(65535);
            }
        }
    }

    @Nested
    class Float {

        @Test
        void patchAndFillDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray values = floatArray(arena, 1.5f, 2.5f);
                Array indices = intArray(arena, 0, 2);
                var sut = new LazySparseFloatArray(F32, 3, 9.0f, values, indices, 0L);

                assertThat(sut.getFloat(0)).isEqualTo(1.5f);
                assertThat(sut.getFloat(1)).isEqualTo(9.0f);
                assertThat(sut.getFloat(2)).isEqualTo(2.5f);
            }
        }

        @Test
        void foldSumsFillAndPatches() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray values = floatArray(arena, 1.5f, 2.5f);
                Array indices = intArray(arena, 1, 3);
                // length=5, fill=10, patches at index 1 and 3
                var sut = new LazySparseFloatArray(F32, 5, 10.0f, values, indices, 0L);

                double sum = sut.fold(0.0, java.lang.Double::sum);

                // 10 + 1.5 + 10 + 2.5 + 10 = 34
                assertThat(sum).isEqualTo(34.0);
            }
        }

        @Test
        void offsetSkipsLeadingPatches() {
            try (Arena arena = Arena.ofConfined()) {
                // length=3 covering abs [4..7), fill=1, patches at abs 4 and 6
                FloatArray values = floatArray(arena, 10.0f, 11.0f, 12.0f);
                Array indices = intArray(arena, 1, 4, 6);
                var sut = new LazySparseFloatArray(F32, 3, 1.0f, values, indices, 4L);

                assertThat(sut.getFloat(0)).isEqualTo(11.0f);
                assertThat(sut.getFloat(1)).isEqualTo(1.0f);
                assertThat(sut.getFloat(2)).isEqualTo(12.0f);
            }
        }

        @Test
        void nullPatchesIsAllFill() {
            // patchValues == null is the no-patch fast path: every position returns fill
            var sut = new LazySparseFloatArray(F32, 3, 42.0f, null, null, 0L);

            assertThat(sut.getFloat(0)).isEqualTo(42.0f);
            assertThat(sut.fold(0.0, java.lang.Double::sum)).isEqualTo(126.0);
        }
    }

    private static LongArray longArray(Arena arena, long... vs) {
        if (vs.length == 0) {
            return new MaterializedLongArray(I64, 0,
                    arena.allocate(1L, 8).asReadOnly().asSlice(0, 0));
        }
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, vs[i]);
        }
        return new MaterializedLongArray(I64, vs.length, seg.asReadOnly());
    }

    private static IntArray intArray(Arena arena, int... vs) {
        if (vs.length == 0) {
            return new MaterializedIntArray(I32, 0,
                    arena.allocate(1L, 4).asReadOnly().asSlice(0, 0));
        }
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, vs[i]);
        }
        return new MaterializedIntArray(I32, vs.length, seg.asReadOnly());
    }

    private static DoubleArray doubleArray(Arena arena, double... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_DOUBLE, i, vs[i]);
        }
        return new MaterializedDoubleArray(F64, vs.length, seg.asReadOnly());
    }

    private static FloatArray floatArray(Arena arena, float... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, vs[i]);
        }
        return new MaterializedFloatArray(F32, vs.length, seg.asReadOnly());
    }

    private static ByteArray byteArray(Arena arena, byte... vs) {
        MemorySegment seg = arena.allocate(vs.length, 1);
        for (int i = 0; i < vs.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, vs[i]);
        }
        return new MaterializedByteArray(I8, vs.length, seg.asReadOnly());
    }

    private static ShortArray shortArray(Arena arena, short... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, vs[i]);
        }
        return new MaterializedShortArray(I16, vs.length, seg.asReadOnly());
    }
}
