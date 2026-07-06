package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy FastLanes RLE records. Cover scalar lookup, constant-run
/// fast path, multi-chunk forEach iteration, fold reduction, and offset slicing.
///
/// Note: FastLanes RLE works in 1024-row chunks. Tests construct the records directly
/// so they exercise the lazy semantics without needing a full encoder round-trip.
class LazyRleArrayTest {

    private static final DType I64 = DType.I64;
    private static final DType I32 = DType.I32;
    private static final DType U8 = DType.U8;
    private static final DType I8 = DType.I8;
    private static final DType U16 = DType.U16;
    private static final DType I16 = DType.I16;
    private static final DType F64 = DType.F64;
    private static final DType F32 = DType.F32;

    @Nested
    class LongDispatch {

        @Test
        void singleChunkAllSameValue_constantRunFastPath() {
            // Given a single 1024-row chunk with 1 distinct value (42).
            // valuesIdxOffsets[0] = 0, indices irrelevant (constant fast path).
            long[] values = {42L};
            int[] indices = new int[1024];
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 1024, values, indices,
                    valuesIdxOffsets, 0L, 1L, 1, 0);

            assertThat(sut.getLong(0)).isEqualTo(42L);
            assertThat(sut.getLong(500)).isEqualTo(42L);
            assertThat(sut.getLong(1023)).isEqualTo(42L);
        }

        @Test
        void singleChunkWithIndices_perRowLookup() {
            // Given one chunk with values [10, 20, 30], indices selecting them in
            // pattern 0,1,2,0,1,2,... over the first 6 rows.
            long[] values = {10L, 20L, 30L};
            int[] indices = new int[1024];
            for (int i = 0; i < 6; i++) {
                indices[i] = i % 3;
            }
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 6, values, indices,
                    valuesIdxOffsets, 0L, 3L, 1, 0);

            var seen = new ArrayList<java.lang.Long>();
            sut.forEachLong(seen::add);

            assertThat(seen).containsExactly(10L, 20L, 30L, 10L, 20L, 30L);
        }

        @Test
        void multiChunkBoundary_walksAcrossChunks() {
            // Given two chunks: chunk 0 = constant 1, chunk 1 = constant 2.
            long[] values = {1L, 2L};
            int[] indices = new int[2 * 1024];  // unused for constant runs
            long[] valuesIdxOffsets = {0L, 1L};
            // length = 1026 covers all of chunk 0 + first 2 rows of chunk 1
            var sut = new LazyRleLongArray(I64, 1026, values, indices,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            assertThat(sut.getLong(0)).isEqualTo(1L);
            assertThat(sut.getLong(1023)).isEqualTo(1L);
            assertThat(sut.getLong(1024)).isEqualTo(2L);
            assertThat(sut.getLong(1025)).isEqualTo(2L);
        }

        @Test
        void offsetSkipsLeadingRows() {
            // Given chunk 0 = constant 5; offset=100 means logical row 0 -> absolute 100.
            long[] values = {5L};
            int[] indices = new int[1024];
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 10, values, indices,
                    valuesIdxOffsets, 0L, 1L, 1, 100);

            assertThat(sut.getLong(0)).isEqualTo(5L);
            assertThat(sut.getLong(9)).isEqualTo(5L);
        }

        @Test
        void foldSumsCorrectly() {
            // Two chunks both constant runs: chunk 0 = 7, chunk 1 = 11. length = 1026.
            long[] values = {7L, 11L};
            int[] indices = new int[2 * 1024];
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleLongArray(I64, 1026, values, indices,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            long sum = sut.fold(0L, java.lang.Long::sum);

            // 1024 * 7 + 2 * 11 = 7168 + 22 = 7190
            assertThat(sum).isEqualTo(7190L);
        }

        @Test
        void getLongLookupIndexedClampAndEmpty() {
            // Given — indexed chunk; index[2] out of range clamps to last value
            int[] idx = new int[1024];
            idx[0] = 0;
            idx[1] = 1;
            idx[2] = 9;
            var x = new LazyRleLongArray(I64, 3, new long[]{10L, 20L, 30L}, idx,
                    new long[]{0L}, 0L, 3L, 1, 0);
            assertThat(x.getLong(0)).isEqualTo(10L);
            assertThat(x.getLong(1)).isEqualTo(20L);
            assertThat(x.getLong(2)).isEqualTo(30L); // clamped

            // empty chunk → zero
            var e = new LazyRleLongArray(I64, 2, new long[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(e.getLong(0)).isZero();
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            // Given — 2 chunks declared but length only spans 5 rows of chunk 0;
            // the fold loop must exit on `emitted < n`, not on chunk exhaustion
            var sut = new LazyRleLongArray(I64, 5, new long[]{1L, 2L}, new int[2048],
                    new long[]{0L, 1L}, 0L, 2L, 2, 0);
            assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(5L);
        }

        @Test
        void forEachIndexedClampAndEmptyChunk() {
            // indexed forEach with out-of-range index → clamps to last value
            int[] idx = new int[1024];
            idx[0] = 0;
            idx[1] = 9;
            var x = new LazyRleLongArray(I64, 2, new long[]{10L, 20L}, idx,
                    new long[]{0L}, 0L, 2L, 1, 0);
            var seen = new ArrayList<java.lang.Long>();
            x.forEachLong(seen::add);
            assertThat(seen).containsExactly(10L, 20L);

            // empty chunk via forEach → zeros
            var e = new LazyRleLongArray(I64, 2, new long[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0);
            var zeros = new ArrayList<java.lang.Long>();
            e.forEachLong(zeros::add);
            assertThat(zeros).containsExactly(0L, 0L);
        }
    }

    @Nested
    class IntDispatch {

        @Test
        void intLookupAndForEach() {
            int[] values = {100, 200};
            int[] indices = new int[1024];
            indices[0] = 0;
            indices[1] = 1;
            indices[2] = 0;
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleIntArray(I32, 3, values, indices,
                    valuesIdxOffsets, 0L, 2L, 1, 0);

            assertThat(sut.getInt(0)).isEqualTo(100);
            assertThat(sut.getInt(1)).isEqualTo(200);
            assertThat(sut.getInt(2)).isEqualTo(100);
        }

        @Test
        void forEachFoldClampAndEmptyChunk() {
            // Given — values [1,2,3]; index[3] out of range → clamps to last (3)
            int[] indices = new int[1024];
            indices[0] = 0;
            indices[1] = 1;
            indices[2] = 2;
            indices[3] = 99;
            var sut = new LazyRleIntArray(I32, 4, new int[]{1, 2, 3}, indices,
                    new long[]{0L}, 0L, 3L, 1, 0);

            assertThat(sut.getInt(0)).isEqualTo(1);
            assertThat(sut.getInt(3)).isEqualTo(3); // getInt lookup clamp path
            var seen = new ArrayList<java.lang.Integer>();
            sut.forEachInt(seen::add);
            assertThat(seen).containsExactly(1, 2, 3, 3);
            assertThat(sut.fold(0, Integer::sum)).isEqualTo(9);

            // empty chunk (0 distinct values) → zero, via getInt, forEach and fold
            var empty = new LazyRleIntArray(I32, 2, new int[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(empty.getInt(0)).isZero();
            var zeros = new ArrayList<java.lang.Integer>();
            empty.forEachInt(zeros::add);
            assertThat(zeros).containsExactly(0, 0);
            assertThat(empty.fold(0, Integer::sum)).isZero();
        }

        @Test
        void getIntConstantRunFastPath() {
            // single distinct value → getInt hits the numChunkValues == 1 lookup branch
            var sut = new LazyRleIntArray(I32, 3, new int[]{42}, new int[1024],
                    new long[]{0L}, 0L, 1L, 1, 0);
            assertThat(sut.getInt(0)).isEqualTo(42);
            assertThat(sut.getInt(2)).isEqualTo(42);
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            // 2 chunks declared, length spans only 5 rows of chunk 0 → loop exits on emitted < n
            var sut = new LazyRleIntArray(I32, 5, new int[]{1, 2}, new int[2048],
                    new long[]{0L, 1L}, 0L, 2L, 2, 0);
            assertThat(sut.fold(0, Integer::sum)).isEqualTo(5);
        }
    }

    @Nested
    class ByteDispatch {

        @Test
        void lookupConstantEmptyIndexedClamp() {
            // constant run (1 distinct)
            var c = new LazyRleByteArray(U8, 3, new byte[]{7}, new int[1024],
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(c.getByte(0)).isEqualTo((byte) 7);
            assertThat(c.fold(0L, Long::sum)).isEqualTo(21L);

            // empty chunk (0 distinct) → zero
            var e = new LazyRleByteArray(U8, 2, new byte[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0, true);
            assertThat(e.getByte(0)).isZero();
            assertThat(e.fold(0L, Long::sum)).isZero();

            // indexed with out-of-range index → clamps to last value
            int[] idx = new int[1024];
            idx[0] = 0;
            idx[1] = 1;
            idx[2] = 2;
            idx[3] = 99;
            var x = new LazyRleByteArray(U8, 4, new byte[]{1, 2, 3}, idx,
                    new long[]{0L}, 0L, 3L, 1, 0, true);
            assertThat(x.getByte(0)).isEqualTo((byte) 1);
            assertThat(x.getByte(3)).isEqualTo((byte) 3); // clamped
            assertThat(x.fold(0L, Long::sum)).isEqualTo(9L);
        }

        @Test
        void getIntWidensSignedAndUnsigned() {
            int[] idx = new int[1024];
            var u = new LazyRleByteArray(U8, 1, new byte[]{(byte) 0xF0}, idx,
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(u.getInt(0)).isEqualTo(240);
            var s = new LazyRleByteArray(I8, 1, new byte[]{(byte) 0xF0}, idx,
                    new long[]{0L}, 0L, 1L, 1, 0, false);
            assertThat(s.getInt(0)).isEqualTo(-16);
        }

        @Test
        void multiChunkFoldSignedWiden() {
            // two constant chunks: 1024×1 + 2×2; signed widening path in foldChunk
            var sut = new LazyRleByteArray(I8, 1026, new byte[]{1, 2}, new int[2048],
                    new long[]{0L, 1L}, 0L, 2L, 2, 0, false);
            assertThat(sut.getByte(1024)).isEqualTo((byte) 2);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(1024L + 4L);
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            var sut = new LazyRleByteArray(U8, 5, new byte[]{1, 2}, new int[2048],
                    new long[]{0L, 1L}, 0L, 2L, 2, 0, true);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(5L);
        }
    }

    @Nested
    class ShortDispatch {

        @Test
        void lookupConstantEmptyIndexedClamp() {
            var c = new LazyRleShortArray(U16, 3, new short[]{7}, new int[1024],
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(c.getShort(0)).isEqualTo((short) 7);
            assertThat(c.fold(0L, Long::sum)).isEqualTo(21L);

            var e = new LazyRleShortArray(U16, 2, new short[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0, true);
            assertThat(e.getShort(0)).isZero();
            assertThat(e.fold(0L, Long::sum)).isZero();

            int[] idx = new int[1024];
            idx[0] = 0;
            idx[1] = 1;
            idx[2] = 2;
            idx[3] = 99;
            var x = new LazyRleShortArray(U16, 4, new short[]{1, 2, 3}, idx,
                    new long[]{0L}, 0L, 3L, 1, 0, true);
            assertThat(x.getShort(0)).isEqualTo((short) 1);
            assertThat(x.getShort(3)).isEqualTo((short) 3); // clamped
            assertThat(x.fold(0L, Long::sum)).isEqualTo(9L);
        }

        @Test
        void getIntWidensSignedAndUnsigned() {
            int[] idx = new int[1024];
            var u = new LazyRleShortArray(U16, 1, new short[]{(short) 0xFF00}, idx,
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(u.getInt(0)).isEqualTo(0xFF00);
            var s = new LazyRleShortArray(I16, 1, new short[]{(short) 0xFF00}, idx,
                    new long[]{0L}, 0L, 1L, 1, 0, false);
            assertThat(s.getInt(0)).isEqualTo((int) (short) 0xFF00);
        }

        @Test
        void multiChunkFoldSignedWiden() {
            var sut = new LazyRleShortArray(I16, 1026, new short[]{1, 2}, new int[2048],
                    new long[]{0L, 1L}, 0L, 2L, 2, 0, false);
            assertThat(sut.getShort(1024)).isEqualTo((short) 2);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(1024L + 4L);
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            var sut = new LazyRleShortArray(U16, 5, new short[]{1, 2}, new int[2048],
                    new long[]{0L, 1L}, 0L, 2L, 2, 0, true);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(5L);
        }
    }

    /// F64 RLE columns are produced by the Python Vortex writer for double weather
    /// columns with long constant runs (issue #209). Fractional values are chosen so a
    /// truncating or int-widening decode bug would surface as a wrong assertion.
    @Nested
    class DoubleDispatch {

        @Test
        void singleChunkAllSameValue_constantRunFastPath() {
            // Given a single 1024-row chunk with 1 distinct value; the constant-run
            // fast path must return it for every row without touching indices.
            double[] values = {2.5};
            int[] indices = new int[1024];
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleDoubleArray(F64, 1024, values, indices,
                    valuesIdxOffsets, 0L, 1L, 1, 0);

            // When / Then
            assertThat(sut.getDouble(0)).isEqualTo(2.5);
            assertThat(sut.getDouble(500)).isEqualTo(2.5);
            assertThat(sut.getDouble(1023)).isEqualTo(2.5);
        }

        @Test
        void singleChunkWithIndices_perRowLookup() {
            // Given one chunk with 3 distinct fractional values selected in a 0,1,2 cycle;
            // fractional values catch any accidental integer narrowing in the value read.
            double[] values = {1.1, 2.2, 3.3};
            int[] indices = new int[1024];
            for (int i = 0; i < 6; i++) {
                indices[i] = i % 3;
            }
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleDoubleArray(F64, 6, values, indices,
                    valuesIdxOffsets, 0L, 3L, 1, 0);

            var result = new ArrayList<Double>();
            sut.forEachDouble(result::add);

            assertThat(result).containsExactly(1.1, 2.2, 3.3, 1.1, 2.2, 3.3);
        }

        @Test
        void multiChunkBoundary_walksAcrossChunks() {
            // Given two constant chunks (chunk 0 = -1.5, chunk 1 = 4.25); the negative
            // value guards against a sign-loss bug and the boundary crosses a chunk.
            double[] values = {-1.5, 4.25};
            int[] indices = new int[2 * 1024];
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleDoubleArray(F64, 1026, values, indices,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            // When / Then — rows straddle the 1024-row chunk boundary
            assertThat(sut.getDouble(0)).isEqualTo(-1.5);
            assertThat(sut.getDouble(1023)).isEqualTo(-1.5);
            assertThat(sut.getDouble(1024)).isEqualTo(4.25);
            assertThat(sut.getDouble(1025)).isEqualTo(4.25);
        }

        @Test
        void offsetSkipsLeadingRows() {
            // Given chunk 0 = constant 5.75; offset=100 maps logical row 0 to absolute 100,
            // matching the slice-with-offset arrays the Python writer emits.
            double[] values = {5.75};
            int[] indices = new int[1024];
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleDoubleArray(F64, 10, values, indices,
                    valuesIdxOffsets, 0L, 1L, 1, 100);

            // When / Then
            assertThat(sut.getDouble(0)).isEqualTo(5.75);
            assertThat(sut.getDouble(9)).isEqualTo(5.75);
        }

        @Test
        void foldSumsFractionalValues() {
            // Given two constant chunks (0.5 and 0.25); a fractional fold sum would be
            // wrong if any element were truncated to a long during decode.
            double[] values = {0.5, 0.25};
            int[] indices = new int[2 * 1024];
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleDoubleArray(F64, 1026, values, indices,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            double result = sut.fold(0.0, Double::sum);

            // 1024 * 0.5 + 2 * 0.25 = 512.0 + 0.5 = 512.5
            assertThat(result).isEqualTo(512.5);
        }

        @Test
        void indexedClampAndEmptyChunk() {
            // Given an indexed chunk whose index[2] overruns the value range: it must
            // clamp to the last value (the writer leaves trailing bits 0 for constant runs).
            int[] idx = new int[1024];
            idx[0] = 0;
            idx[1] = 1;
            idx[2] = 9;
            var sut = new LazyRleDoubleArray(F64, 3, new double[]{1.0, 2.0, 3.0}, idx,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When / Then — out-of-range index clamps to last
            assertThat(sut.getDouble(2)).isEqualTo(3.0);

            // empty chunk (0 distinct values) → 0.0 via getDouble and forEach
            var empty = new LazyRleDoubleArray(F64, 2, new double[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(empty.getDouble(0)).isZero();
            var zeros = new ArrayList<Double>();
            empty.forEachDouble(zeros::add);
            assertThat(zeros).containsExactly(0.0, 0.0);
        }
    }

    /// F32 RLE columns follow the same wire layout as F64 with a 4-byte value child
    /// (issue #209); [LazyRleFloatArray] materializes rather than exposing a forEach.
    @Nested
    class FloatDispatch {

        @Test
        void singleChunkWithIndices_perRowLookup() {
            // Given one chunk with 3 distinct float values in a 0,1,2 cycle; fractional
            // 32-bit values catch a wrong-width read (reading 8 bytes as a double).
            float[] values = {1.5f, 2.5f, 3.5f};
            int[] indices = new int[1024];
            for (int i = 0; i < 6; i++) {
                indices[i] = i % 3;
            }
            var sut = new LazyRleFloatArray(F32, 6, values, indices,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(1.5f);
            assertThat(sut.getFloat(1)).isEqualTo(2.5f);
            assertThat(sut.getFloat(5)).isEqualTo(3.5f);
        }

        @Test
        void constantRunFastPathAndMultiChunk() {
            // Given two constant chunks (chunk 0 = -2.25, chunk 1 = 8.75); crosses the
            // 1024-row boundary and the negative value guards against sign loss.
            var sut = new LazyRleFloatArray(F32, 1026, new float[]{-2.25f, 8.75f},
                    new int[2 * 1024], new long[]{0L, 1L}, 0L, 2L, 2, 0);

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(-2.25f);
            assertThat(sut.getFloat(1024)).isEqualTo(8.75f);
        }

        @Test
        void indexedClampAndEmptyChunk() {
            // Given an indexed chunk whose index[2] overruns the value range → clamp to last.
            int[] idx = new int[1024];
            idx[0] = 0;
            idx[1] = 1;
            idx[2] = 9;
            var sut = new LazyRleFloatArray(F32, 3, new float[]{1.0f, 2.0f, 3.0f}, idx,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When / Then — clamped to last value
            assertThat(sut.getFloat(2)).isEqualTo(3.0f);

            // empty chunk (0 distinct values) → 0.0f
            var empty = new LazyRleFloatArray(F32, 2, new float[0], new int[1024],
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(empty.getFloat(0)).isZero();
        }

        @Test
        void materializeDecodesEveryRow() {
            // Given a mixed chunk; materialize must emit each logical row once, honoring
            // the constant-run fast path only within a chunk (here indices vary).
            float[] values = {10.5f, 20.5f};
            int[] indices = new int[1024];
            indices[0] = 0;
            indices[1] = 1;
            indices[2] = 1;
            var sut = new LazyRleFloatArray(F32, 3, values, indices,
                    new long[]{0L}, 0L, 2L, 1, 0);

            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                var result = sut.materialize(arena);

                assertThat(result.getAtIndex(io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT, 0)).isEqualTo(10.5f);
                assertThat(result.getAtIndex(io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT, 1)).isEqualTo(20.5f);
                assertThat(result.getAtIndex(io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT, 2)).isEqualTo(20.5f);
            }
        }
    }
}
