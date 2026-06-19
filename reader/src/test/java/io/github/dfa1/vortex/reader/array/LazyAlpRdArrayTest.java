package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy ALP-RD records. Cover dict + right reconstruction for
/// both F64 and F32, and the patches path that overrides the dict lookup at
/// specific indices.
class LazyAlpRdArrayTest {

    private static final DType F64 = new DType.Primitive(PType.F64, false);
    private static final DType F32 = new DType.Primitive(PType.F32, false);

    @Nested
    class DoubleDispatch {

        @Test
        void reconstructsBitsViaDict() {
            // Given 3.14 has bit pattern with a particular left/right split.
            // We pick rightBitWidth = 48 → left = top 16 bits.
            try (Arena arena = Arena.ofConfined()) {
                int rightBitWidth = 48;
                double[] sample = {3.14, 2.71, -1.0};
                short[] dict = new short[sample.length];
                long[] rights = new long[sample.length];
                long rightMask = (1L << rightBitWidth) - 1;
                for (int i = 0; i < sample.length; i++) {
                    long bits = Double.doubleToRawLongBits(sample[i]);
                    dict[i] = (short) ((bits >>> rightBitWidth) & 0xFFFF);
                    rights[i] = bits & rightMask;
                }
                // Each row uses code i.
                ShortArray leftArr = shortArray(arena, (short) 0, (short) 1, (short) 2);
                LongArray rightArr = longArray(arena, rights);

                // When
                var sut = new LazyAlpRdDoubleArray(F64, sample.length, dict, rightBitWidth,
                        leftArr, rightArr, null, new short[0], 0L);

                // Then
                for (int i = 0; i < sample.length; i++) {
                    assertThat(sut.getDouble(i)).as("index %d", i).isEqualTo(sample[i]);
                }
            }
        }

        @Test
        void patchOverridesDictAtPatchedIndex() {
            // Given a single-entry dict for non-patched rows, plus a patch overriding row 1
            // with a different left value.
            try (Arena arena = Arena.ofConfined()) {
                int rightBitWidth = 48;
                double base = 1.5;
                double patched = -42.0;
                long baseBits = Double.doubleToRawLongBits(base);
                long patchedBits = Double.doubleToRawLongBits(patched);
                long rightMask = (1L << rightBitWidth) - 1;

                short[] dict = {(short) ((baseBits >>> rightBitWidth) & 0xFFFF)};
                ShortArray leftArr = shortArray(arena, (short) 0, (short) 0, (short) 0);
                LongArray rightArr = longArray(arena,
                        baseBits & rightMask, patchedBits & rightMask, baseBits & rightMask);
                Array patchIdx = intArray(arena, 1);
                short[] patchLeft = {(short) ((patchedBits >>> rightBitWidth) & 0xFFFF)};

                // When
                var sut = new LazyAlpRdDoubleArray(F64, 3, dict, rightBitWidth,
                        leftArr, rightArr, patchIdx, patchLeft, 0L);

                // Then
                assertThat(sut.getDouble(0)).isEqualTo(base);
                assertThat(sut.getDouble(1)).isEqualTo(patched);
                assertThat(sut.getDouble(2)).isEqualTo(base);
            }
        }

        @Test
        void forEachAndFoldVisitAllRows() {
            // Given — three values reconstructed dict-only
            try (Arena arena = Arena.ofConfined()) {
                int rightBitWidth = 48;
                double[] sample = {1.0, 2.0, 4.0};
                short[] dict = new short[sample.length];
                long[] rights = new long[sample.length];
                long rightMask = (1L << rightBitWidth) - 1;
                short[] codes = new short[sample.length];
                for (int i = 0; i < sample.length; i++) {
                    long bits = Double.doubleToRawLongBits(sample[i]);
                    dict[i] = (short) ((bits >>> rightBitWidth) & 0xFFFF);
                    rights[i] = bits & rightMask;
                    codes[i] = (short) i;
                }
                var sut = new LazyAlpRdDoubleArray(F64, sample.length, dict, rightBitWidth,
                        shortArray(arena, codes), longArray(arena, rights), null, new short[0], 0L);

                // When
                List<Double> seen = new ArrayList<>();
                sut.forEachDouble(seen::add);

                // Then
                assertThat(seen).containsExactly(1.0, 2.0, 4.0);
                assertThat(sut.fold(0.0, Double::sum)).isEqualTo(7.0);
            }
        }
    }

    @Nested
    class FloatDispatch {

        @Test
        void reconstructsBitsViaDict() {
            try (Arena arena = Arena.ofConfined()) {
                int rightBitWidth = 16;
                float[] sample = {1.5f, -2.25f};
                short[] dict = new short[sample.length];
                int[] rights = new int[sample.length];
                int rightMask = (1 << rightBitWidth) - 1;
                for (int i = 0; i < sample.length; i++) {
                    int bits = Float.floatToRawIntBits(sample[i]);
                    dict[i] = (short) ((bits >>> rightBitWidth) & 0xFFFF);
                    rights[i] = bits & rightMask;
                }
                ShortArray leftArr = shortArray(arena, (short) 0, (short) 1);
                IntArray rightArr = intArray(arena, rights);

                var sut = new LazyAlpRdFloatArray(F32, sample.length, dict, rightBitWidth,
                        leftArr, rightArr, null, new short[0], 0L);

                assertThat(sut.getFloat(0)).isEqualTo(1.5f);
                assertThat(sut.getFloat(1)).isEqualTo(-2.25f);
            }
        }

        @Test
        void patchOverridesDictAndFoldSums() {
            // Given — row 1 patched, rows 0/2 dict; also exercises fold + the non-patch lookup
            try (Arena arena = Arena.ofConfined()) {
                int rightBitWidth = 16;
                float base = 1.5f;
                float patched = -3.0f;
                int baseBits = Float.floatToRawIntBits(base);
                int patchedBits = Float.floatToRawIntBits(patched);
                int rightMask = (1 << rightBitWidth) - 1;

                short[] dict = {(short) ((baseBits >>> rightBitWidth) & 0xFFFF)};
                ShortArray leftArr = shortArray(arena, (short) 0, (short) 0, (short) 0);
                IntArray rightArr = intArray(arena,
                        baseBits & rightMask, patchedBits & rightMask, baseBits & rightMask);
                Array patchIdx = intArray(arena, 1);
                short[] patchLeft = {(short) ((patchedBits >>> rightBitWidth) & 0xFFFF)};

                var sut = new LazyAlpRdFloatArray(F32, 3, dict, rightBitWidth,
                        leftArr, rightArr, patchIdx, patchLeft, 0L);

                // Then
                assertThat(sut.getFloat(0)).isEqualTo(base);
                assertThat(sut.getFloat(1)).isEqualTo(patched);
                assertThat(sut.getFloat(2)).isEqualTo(base);
                assertThat(sut.fold(0.0, Double::sum))
                        .isEqualTo((double) base + patched + base);
            }
        }
    }

    private static ShortArray shortArray(Arena arena, short... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, vs[i]);
        }
        return new MaterializedShortArray(new DType.Primitive(PType.U16, false), vs.length, seg.asReadOnly());
    }

    private static IntArray intArray(Arena arena, int... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, vs[i]);
        }
        return new MaterializedIntArray(new DType.Primitive(PType.I32, false), vs.length, seg.asReadOnly());
    }

    private static LongArray longArray(Arena arena, long... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, vs[i]);
        }
        return new MaterializedLongArray(new DType.Primitive(PType.I64, false), vs.length, seg.asReadOnly());
    }
}
