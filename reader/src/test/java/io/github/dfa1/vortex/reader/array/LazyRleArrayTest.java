package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.testing.TestSegments;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy FastLanes RLE records. Cover scalar lookup, constant-run
/// fast path, multi-chunk forEach iteration, fold reduction, and offset slicing.
///
/// Note: FastLanes RLE works in 1024-row chunks. Tests construct the records directly
/// so they exercise the lazy semantics without needing a full encoder round-trip.
/// Values and indices are raw little-endian [MemorySegment]s — the records read the
/// compressed payload straight out of the mmapped file, never a heap copy — so the
/// fixtures are built with [TestSegments] and the `u8Indices`/`u16Indices` helpers below.
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
            MemorySegment values = TestSegments.leLongs(42L);
            MemorySegment indices = u8Indices(1024);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 1024, values, indices, false,
                    valuesIdxOffsets, 0L, 1L, 1, 0);

            assertThat(sut.getLong(0)).isEqualTo(42L);
            assertThat(sut.getLong(500)).isEqualTo(42L);
            assertThat(sut.getLong(1023)).isEqualTo(42L);
        }

        @Test
        void singleChunkWithIndices_perRowLookup() {
            // Given one chunk with values [10, 20, 30], indices selecting them in
            // pattern 0,1,2,0,1,2,... over the first 6 rows.
            MemorySegment values = TestSegments.leLongs(10L, 20L, 30L);
            MemorySegment indices = u8Indices(1024, 0, 1, 2, 0, 1, 2);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 6, values, indices, false,
                    valuesIdxOffsets, 0L, 3L, 1, 0);

            var seen = new ArrayList<java.lang.Long>();
            sut.forEachLong(seen::add);

            assertThat(seen).containsExactly(10L, 20L, 30L, 10L, 20L, 30L);
        }

        @Test
        void u16Indices_perRowLookupAndForEach() {
            // Given the same chunk with a u16 index table: FastLanes widens the index
            // width once a chunk holds more than 256 runs, so both widths must resolve
            // identically. Guards the wideIndices branch in getLong and processChunk.
            MemorySegment values = TestSegments.leLongs(10L, 20L, 30L);
            MemorySegment indices = u16Indices(1024, 0, 1, 2, 0, 1, 2);
            var sut = new LazyRleLongArray(I64, 6, values, indices, true,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When
            var result = new ArrayList<java.lang.Long>();
            sut.forEachLong(result::add);

            // Then
            assertThat(sut.getLong(4)).isEqualTo(20L);
            assertThat(result).containsExactly(10L, 20L, 30L, 10L, 20L, 30L);
        }

        @Test
        void multiChunkBoundary_walksAcrossChunks() {
            // Given two chunks: chunk 0 = constant 1, chunk 1 = constant 2.
            MemorySegment values = TestSegments.leLongs(1L, 2L);
            MemorySegment indices = u8Indices(2 * 1024);  // unused for constant runs
            long[] valuesIdxOffsets = {0L, 1L};
            // length = 1026 covers all of chunk 0 + first 2 rows of chunk 1
            var sut = new LazyRleLongArray(I64, 1026, values, indices, false,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            assertThat(sut.getLong(0)).isEqualTo(1L);
            assertThat(sut.getLong(1023)).isEqualTo(1L);
            assertThat(sut.getLong(1024)).isEqualTo(2L);
            assertThat(sut.getLong(1025)).isEqualTo(2L);
        }

        @Test
        void offsetSkipsLeadingRows() {
            // Given chunk 0 = constant 5; offset=100 means logical row 0 -> absolute 100.
            MemorySegment values = TestSegments.leLongs(5L);
            MemorySegment indices = u8Indices(1024);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 10, values, indices, false,
                    valuesIdxOffsets, 0L, 1L, 1, 100);

            assertThat(sut.getLong(0)).isEqualTo(5L);
            assertThat(sut.getLong(9)).isEqualTo(5L);
        }

        @Test
        void foldSumsCorrectly() {
            // Two chunks both constant runs: chunk 0 = 7, chunk 1 = 11. length = 1026.
            MemorySegment values = TestSegments.leLongs(7L, 11L);
            MemorySegment indices = u8Indices(2 * 1024);
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleLongArray(I64, 1026, values, indices, false,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            long sum = sut.fold(0L, java.lang.Long::sum);

            // 1024 * 7 + 2 * 11 = 7168 + 22 = 7190
            assertThat(sum).isEqualTo(7190L);
        }

        @Test
        void getLongLookupIndexedClampAndEmpty() {
            // Given — indexed chunk; index[2] out of range clamps to last value
            MemorySegment idx = u8Indices(1024, 0, 1, 9);
            var x = new LazyRleLongArray(I64, 3, TestSegments.leLongs(10L, 20L, 30L), idx, false,
                    new long[]{0L}, 0L, 3L, 1, 0);
            assertThat(x.getLong(0)).isEqualTo(10L);
            assertThat(x.getLong(1)).isEqualTo(20L);
            assertThat(x.getLong(2)).isEqualTo(30L); // clamped

            // empty chunk → zero
            var e = new LazyRleLongArray(I64, 2, TestSegments.leLongs(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(e.getLong(0)).isZero();
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            // Given — 2 chunks declared but length only spans 5 rows of chunk 0;
            // the fold loop must exit on `emitted < n`, not on chunk exhaustion
            var sut = new LazyRleLongArray(I64, 5, TestSegments.leLongs(1L, 2L), u8Indices(2048), false,
                    new long[]{0L, 1L}, 0L, 2L, 2, 0);
            assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(5L);
        }

        @Test
        void forEachIndexedClampAndEmptyChunk() {
            // indexed forEach with out-of-range index → clamps to last value
            MemorySegment idx = u8Indices(1024, 0, 9);
            var x = new LazyRleLongArray(I64, 2, TestSegments.leLongs(10L, 20L), idx, false,
                    new long[]{0L}, 0L, 2L, 1, 0);
            var seen = new ArrayList<java.lang.Long>();
            x.forEachLong(seen::add);
            assertThat(seen).containsExactly(10L, 20L);

            // empty chunk via forEach → zeros
            var e = new LazyRleLongArray(I64, 2, TestSegments.leLongs(), u8Indices(1024), false,
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
            MemorySegment values = TestSegments.leInts(100, 200);
            MemorySegment indices = u8Indices(1024, 0, 1, 0);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleIntArray(I32, 3, values, indices, false,
                    valuesIdxOffsets, 0L, 2L, 1, 0);

            assertThat(sut.getInt(0)).isEqualTo(100);
            assertThat(sut.getInt(1)).isEqualTo(200);
            assertThat(sut.getInt(2)).isEqualTo(100);
        }

        @Test
        void forEachFoldClampAndEmptyChunk() {
            // Given — values [1,2,3]; index[3] out of range → clamps to last (3)
            MemorySegment indices = u8Indices(1024, 0, 1, 2, 99);
            var sut = new LazyRleIntArray(I32, 4, TestSegments.leInts(1, 2, 3), indices, false,
                    new long[]{0L}, 0L, 3L, 1, 0);

            assertThat(sut.getInt(0)).isEqualTo(1);
            assertThat(sut.getInt(3)).isEqualTo(3); // getInt lookup clamp path
            var seen = new ArrayList<java.lang.Integer>();
            sut.forEachInt(seen::add);
            assertThat(seen).containsExactly(1, 2, 3, 3);
            assertThat(sut.fold(0, Integer::sum)).isEqualTo(9);

            // empty chunk (0 distinct values) → zero, via getInt, forEach and fold
            var empty = new LazyRleIntArray(I32, 2, TestSegments.leInts(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(empty.getInt(0)).isZero();
            var zeros = new ArrayList<java.lang.Integer>();
            empty.forEachInt(zeros::add);
            assertThat(zeros).containsExactly(0, 0);
            assertThat(empty.fold(0, Integer::sum)).isZero();
        }

        @Test
        void u16IndicesClampAndForEach() {
            // Given — a u16 index table whose last slot overruns the value range; both the
            // scalar and the chunk-loop wideIndices branches must clamp to the last value.
            MemorySegment indices = u16Indices(1024, 0, 1, 2, 300);
            var sut = new LazyRleIntArray(I32, 4, TestSegments.leInts(1, 2, 3), indices, true,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When
            var result = new ArrayList<java.lang.Integer>();
            sut.forEachInt(result::add);

            // Then
            assertThat(sut.getInt(3)).isEqualTo(3);
            assertThat(result).containsExactly(1, 2, 3, 3);
        }

        @Test
        void getIntConstantRunFastPath() {
            // single distinct value → getInt hits the numChunkValues == 1 lookup branch
            var sut = new LazyRleIntArray(I32, 3, TestSegments.leInts(42), u8Indices(1024), false,
                    new long[]{0L}, 0L, 1L, 1, 0);
            assertThat(sut.getInt(0)).isEqualTo(42);
            assertThat(sut.getInt(2)).isEqualTo(42);
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            // 2 chunks declared, length spans only 5 rows of chunk 0 → loop exits on emitted < n
            var sut = new LazyRleIntArray(I32, 5, TestSegments.leInts(1, 2), u8Indices(2048), false,
                    new long[]{0L, 1L}, 0L, 2L, 2, 0);
            assertThat(sut.fold(0, Integer::sum)).isEqualTo(5);
        }
    }

    @Nested
    class ByteDispatch {

        @Test
        void lookupConstantEmptyIndexedClamp() {
            // constant run (1 distinct)
            var c = new LazyRleByteArray(U8, 3, bytes(7), u8Indices(1024), false,
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(c.getByte(0)).isEqualTo((byte) 7);
            assertThat(c.fold(0L, Long::sum)).isEqualTo(21L);

            // empty chunk (0 distinct) → zero
            var e = new LazyRleByteArray(U8, 2, bytes(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0, true);
            assertThat(e.getByte(0)).isZero();
            assertThat(e.fold(0L, Long::sum)).isZero();

            // indexed with out-of-range index → clamps to last value
            MemorySegment idx = u8Indices(1024, 0, 1, 2, 99);
            var x = new LazyRleByteArray(U8, 4, bytes(1, 2, 3), idx, false,
                    new long[]{0L}, 0L, 3L, 1, 0, true);
            assertThat(x.getByte(0)).isEqualTo((byte) 1);
            assertThat(x.getByte(3)).isEqualTo((byte) 3); // clamped
            assertThat(x.fold(0L, Long::sum)).isEqualTo(9L);
        }

        @Test
        void getIntWidensSignedAndUnsigned() {
            MemorySegment idx = u8Indices(1024);
            var u = new LazyRleByteArray(U8, 1, bytes(0xF0), idx, false,
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(u.getInt(0)).isEqualTo(240);
            var s = new LazyRleByteArray(I8, 1, bytes(0xF0), idx, false,
                    new long[]{0L}, 0L, 1L, 1, 0, false);
            assertThat(s.getInt(0)).isEqualTo(-16);
        }

        @Test
        void multiChunkFoldSignedWiden() {
            // two constant chunks: 1024×1 + 2×2; signed widening path in foldChunk
            var sut = new LazyRleByteArray(I8, 1026, bytes(1, 2), u8Indices(2048), false,
                    new long[]{0L, 1L}, 0L, 2L, 2, 0, false);
            assertThat(sut.getByte(1024)).isEqualTo((byte) 2);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(1024L + 4L);
        }

        @Test
        void u16IndicesFoldWidens() {
            // Given — a u16 index table over a 3-value pool; the wideIndices fold branch
            // must read the same runs the u8 branch would.
            var sut = new LazyRleByteArray(I8, 4, bytes(1, 2, 3), u16Indices(1024, 0, 1, 2, 300), true,
                    new long[]{0L}, 0L, 3L, 1, 0, false);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then — 1 + 2 + 3 + 3 (last index clamped)
            assertThat(result).isEqualTo(9L);
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            var sut = new LazyRleByteArray(U8, 5, bytes(1, 2), u8Indices(2048), false,
                    new long[]{0L, 1L}, 0L, 2L, 2, 0, true);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(5L);
        }
    }

    @Nested
    class ShortDispatch {

        @Test
        void lookupConstantEmptyIndexedClamp() {
            var c = new LazyRleShortArray(U16, 3, TestSegments.leShorts((short) 7), u8Indices(1024), false,
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(c.getShort(0)).isEqualTo((short) 7);
            assertThat(c.fold(0L, Long::sum)).isEqualTo(21L);

            var e = new LazyRleShortArray(U16, 2, TestSegments.leShorts(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0, true);
            assertThat(e.getShort(0)).isZero();
            assertThat(e.fold(0L, Long::sum)).isZero();

            MemorySegment idx = u8Indices(1024, 0, 1, 2, 99);
            var x = new LazyRleShortArray(U16, 4, TestSegments.leShorts((short) 1, (short) 2, (short) 3), idx, false,
                    new long[]{0L}, 0L, 3L, 1, 0, true);
            assertThat(x.getShort(0)).isEqualTo((short) 1);
            assertThat(x.getShort(3)).isEqualTo((short) 3); // clamped
            assertThat(x.fold(0L, Long::sum)).isEqualTo(9L);
        }

        @Test
        void u16IndicesFoldWidens() {
            // Given — a u16 index table over a 3-value pool; the wideIndices fold branch must
            // read the same runs the u8 branch does, including the clamp on the last slot.
            var sut = new LazyRleShortArray(I16, 4, TestSegments.leShorts((short) 1, (short) 2, (short) 3),
                    u16Indices(1024, 0, 1, 2, 300), true, new long[]{0L}, 0L, 3L, 1, 0, false);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then — 1 + 2 + 3 + 3 (last index clamped)
            assertThat(sut.getShort(3)).isEqualTo((short) 3);
            assertThat(result).isEqualTo(9L);
        }

        @Test
        void getIntWidensSignedAndUnsigned() {
            MemorySegment idx = u8Indices(1024);
            var u = new LazyRleShortArray(U16, 1, TestSegments.leShorts((short) 0xFF00), idx, false,
                    new long[]{0L}, 0L, 1L, 1, 0, true);
            assertThat(u.getInt(0)).isEqualTo(0xFF00);
            var s = new LazyRleShortArray(I16, 1, TestSegments.leShorts((short) 0xFF00), idx, false,
                    new long[]{0L}, 0L, 1L, 1, 0, false);
            assertThat(s.getInt(0)).isEqualTo((int) (short) 0xFF00);
        }

        @Test
        void multiChunkFoldSignedWiden() {
            var sut = new LazyRleShortArray(I16, 1026, TestSegments.leShorts((short) 1, (short) 2),
                    u8Indices(2048), false, new long[]{0L, 1L}, 0L, 2L, 2, 0, false);
            assertThat(sut.getShort(1024)).isEqualTo((short) 2);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(1024L + 4L);
        }

        @Test
        void foldStopsWhenLengthSatisfiedBeforeChunksExhausted() {
            var sut = new LazyRleShortArray(U16, 5, TestSegments.leShorts((short) 1, (short) 2),
                    u8Indices(2048), false, new long[]{0L, 1L}, 0L, 2L, 2, 0, true);
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
            MemorySegment values = TestSegments.leDoubles(2.5);
            MemorySegment indices = u8Indices(1024);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleDoubleArray(F64, 1024, values, indices, false,
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
            MemorySegment values = TestSegments.leDoubles(1.1, 2.2, 3.3);
            MemorySegment indices = u8Indices(1024, 0, 1, 2, 0, 1, 2);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleDoubleArray(F64, 6, values, indices, false,
                    valuesIdxOffsets, 0L, 3L, 1, 0);

            var result = new ArrayList<Double>();
            sut.forEachDouble(result::add);

            assertThat(result).containsExactly(1.1, 2.2, 3.3, 1.1, 2.2, 3.3);
        }

        @Test
        void multiChunkBoundary_walksAcrossChunks() {
            // Given two constant chunks (chunk 0 = -1.5, chunk 1 = 4.25); the negative
            // value guards against a sign-loss bug and the boundary crosses a chunk.
            MemorySegment values = TestSegments.leDoubles(-1.5, 4.25);
            MemorySegment indices = u8Indices(2 * 1024);
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleDoubleArray(F64, 1026, values, indices, false,
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
            MemorySegment values = TestSegments.leDoubles(5.75);
            MemorySegment indices = u8Indices(1024);
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleDoubleArray(F64, 10, values, indices, false,
                    valuesIdxOffsets, 0L, 1L, 1, 100);

            // When / Then
            assertThat(sut.getDouble(0)).isEqualTo(5.75);
            assertThat(sut.getDouble(9)).isEqualTo(5.75);
        }

        @Test
        void foldSumsFractionalValues() {
            // Given two constant chunks (0.5 and 0.25); a fractional fold sum would be
            // wrong if any element were truncated to a long during decode.
            MemorySegment values = TestSegments.leDoubles(0.5, 0.25);
            MemorySegment indices = u8Indices(2 * 1024);
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleDoubleArray(F64, 1026, values, indices, false,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            double result = sut.fold(0.0, Double::sum);

            // 1024 * 0.5 + 2 * 0.25 = 512.0 + 0.5 = 512.5
            assertThat(result).isEqualTo(512.5);
        }

        @Test
        void preservesNaNAndInfinityBitPatterns() {
            // Given a specific quiet-NaN payload plus +/-inf; the decode must copy raw
            // IEEE-754 bits verbatim, so bit-exact equality (not value equality, since
            // NaN != NaN) is the right assertion.
            double nanPayload = Double.longBitsToDouble(0x7FF8_0000_0000_002AL);
            MemorySegment values = TestSegments.leDoubles(
                    nanPayload, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            MemorySegment indices = u8Indices(1024, 0, 1, 2);
            var sut = new LazyRleDoubleArray(F64, 3, values, indices, false,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When / Then — raw bits round-trip unchanged
            assertThat(Double.doubleToRawLongBits(sut.getDouble(0)))
                    .isEqualTo(0x7FF8_0000_0000_002AL);
            assertThat(sut.getDouble(1)).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(sut.getDouble(2)).isEqualTo(Double.NEGATIVE_INFINITY);
        }

        @Test
        void u16IndicesPerRowLookupAndForEach() {
            // Given — the same 3-value pool addressed by a u16 index table; both the scalar and
            // the chunk-loop wideIndices branches must resolve exactly as the u8 ones do.
            MemorySegment values = TestSegments.leDoubles(1.1, 2.2, 3.3);
            var sut = new LazyRleDoubleArray(F64, 6, values, u16Indices(1024, 0, 1, 2, 0, 1, 2), true,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When
            var result = new ArrayList<Double>();
            sut.forEachDouble(result::add);

            // Then
            assertThat(sut.getDouble(4)).isEqualTo(2.2);
            assertThat(result).containsExactly(1.1, 2.2, 3.3, 1.1, 2.2, 3.3);
        }

        @Test
        void indexedClampAndEmptyChunk() {
            // Given an indexed chunk whose index[2] overruns the value range: it must
            // clamp to the last value (the writer leaves trailing bits 0 for constant runs).
            MemorySegment idx = u8Indices(1024, 0, 1, 9);
            var sut = new LazyRleDoubleArray(F64, 3, TestSegments.leDoubles(1.0, 2.0, 3.0), idx, false,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When / Then — out-of-range index clamps to last
            assertThat(sut.getDouble(2)).isEqualTo(3.0);

            // empty chunk (0 distinct values) → 0.0 via getDouble and forEach
            var empty = new LazyRleDoubleArray(F64, 2, TestSegments.leDoubles(), u8Indices(1024), false,
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
            MemorySegment values = TestSegments.leFloats(1.5f, 2.5f, 3.5f);
            MemorySegment indices = u8Indices(1024, 0, 1, 2, 0, 1, 2);
            var sut = new LazyRleFloatArray(F32, 6, values, indices, false,
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
            var sut = new LazyRleFloatArray(F32, 1026, TestSegments.leFloats(-2.25f, 8.75f),
                    u8Indices(2 * 1024), false, new long[]{0L, 1L}, 0L, 2L, 2, 0);

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(-2.25f);
            assertThat(sut.getFloat(1024)).isEqualTo(8.75f);
        }

        @Test
        void indexedClampAndEmptyChunk() {
            // Given an indexed chunk whose index[2] overruns the value range → clamp to last.
            MemorySegment idx = u8Indices(1024, 0, 1, 9);
            var sut = new LazyRleFloatArray(F32, 3, TestSegments.leFloats(1.0f, 2.0f, 3.0f), idx, false,
                    new long[]{0L}, 0L, 3L, 1, 0);

            // When / Then — clamped to last value
            assertThat(sut.getFloat(2)).isEqualTo(3.0f);

            // empty chunk (0 distinct values) → 0.0f
            var empty = new LazyRleFloatArray(F32, 2, TestSegments.leFloats(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0);
            assertThat(empty.getFloat(0)).isZero();
        }

        @Test
        void materializeConstantRunAndEmptyChunk() {
            // Given a constant-run chunk (1 distinct value) and, separately, an empty chunk
            // (0 distinct values): materialize's processChunk fast path must emit the lone
            // value — or 0.0f for the empty pool — for every row without touching indices.
            var constant = new LazyRleFloatArray(F32, 3, TestSegments.leFloats(6.25f), u8Indices(1024), false,
                    new long[]{0L}, 0L, 1L, 1, 0);
            var empty = new LazyRleFloatArray(F32, 2, TestSegments.leFloats(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0);

            // When / Then
            try (var arena = Arena.ofConfined()) {
                var constantResult = constant.materialize(arena);
                assertThat(constantResult.getAtIndex(VortexFormat.LE_FLOAT, 0)).isEqualTo(6.25f);
                assertThat(constantResult.getAtIndex(VortexFormat.LE_FLOAT, 2)).isEqualTo(6.25f);

                var emptyResult = empty.materialize(arena);
                assertThat(emptyResult.getAtIndex(VortexFormat.LE_FLOAT, 0)).isEqualTo(0.0f);
                assertThat(emptyResult.getAtIndex(VortexFormat.LE_FLOAT, 1)).isEqualTo(0.0f);
            }
        }

        @Test
        void materializeDecodesEveryRow() {
            // Given a mixed chunk; materialize must emit each logical row once, honoring
            // the constant-run fast path only within a chunk (here indices vary).
            MemorySegment values = TestSegments.leFloats(10.5f, 20.5f);
            MemorySegment indices = u8Indices(1024, 0, 1, 1);
            var sut = new LazyRleFloatArray(F32, 3, values, indices, false,
                    new long[]{0L}, 0L, 2L, 1, 0);

            // When
            try (var arena = Arena.ofConfined()) {
                var result = sut.materialize(arena);

                // Then — each logical row decodes exactly (row 2 reuses value 1 via its index)
                assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, 0)).isEqualTo(10.5f);
                assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, 1)).isEqualTo(20.5f);
                assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, 2)).isEqualTo(20.5f);
            }
        }

        @Test
        void materializeWithU16Indices() {
            // Given the same mixed chunk with a u16 index table; the wideIndices branch of
            // materialize must produce byte-identical output to the u8 branch.
            MemorySegment values = TestSegments.leFloats(10.5f, 20.5f);
            var sut = new LazyRleFloatArray(F32, 3, values, u16Indices(1024, 0, 1, 1), true,
                    new long[]{0L}, 0L, 2L, 1, 0);

            // When
            try (var arena = Arena.ofConfined()) {
                var result = sut.materialize(arena);

                // Then
                assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, 0)).isEqualTo(10.5f);
                assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, 1)).isEqualTo(20.5f);
                assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, 2)).isEqualTo(20.5f);
            }
        }
    }

    /// A Bool RLE column carries the same chunk/index machinery with a [BoolArray] value pool;
    /// mirrors `LongDispatch` because the record shares none of the numeric records' code.
    @Nested
    class BoolDispatch {

        @Test
        void singleChunkAllSameValue_constantRunFastPath() {
            // Given a single 1024-row chunk with 1 distinct value (true); indices are irrelevant
            // on the constant-run path, which is the common shape for a boolean column.
            var sut = new LazyRleBoolArray(DType.BOOL, 1024, bits(true), u8Indices(1024), false,
                    new long[]{0L}, 0L, 1L, 1, 0);

            // When / Then
            assertThat(sut.getBoolean(0)).isTrue();
            assertThat(sut.getBoolean(500)).isTrue();
            assertThat(sut.getBoolean(1023)).isTrue();
        }

        @Test
        void singleChunkWithIndices_perRowLookup() {
            // Given one chunk alternating between the two values a boolean chunk can hold.
            var sut = new LazyRleBoolArray(DType.BOOL, 4, bits(false, true), u8Indices(1024, 0, 1, 1, 0),
                    false, new long[]{0L}, 0L, 2L, 1, 0);

            // When
            var result = new ArrayList<Boolean>();
            sut.forEachBoolean(result::add);

            // Then
            assertThat(sut.getBoolean(1)).isTrue();
            assertThat(result).containsExactly(false, true, true, false);
        }

        @Test
        void u16IndicesPerRowLookupAndForEach() {
            // Given — the same runs addressed by a u16 index table; guards the record's
            // wideIndices branch, which no numeric record's test can cover for it.
            var sut = new LazyRleBoolArray(DType.BOOL, 4, bits(false, true),
                    u16Indices(1024, 0, 1, 1, 0), true, new long[]{0L}, 0L, 2L, 1, 0);

            // When
            var result = new ArrayList<Boolean>();
            sut.forEachBoolean(result::add);

            // Then
            assertThat(sut.getBoolean(2)).isTrue();
            assertThat(result).containsExactly(false, true, true, false);
        }

        @Test
        void multiChunkBoundaryAndOffset() {
            // Given two constant chunks (chunk 0 = false, chunk 1 = true) and an offset that
            // starts the logical array inside chunk 0, the slice shape the writer emits.
            var sut = new LazyRleBoolArray(DType.BOOL, 1026, bits(false, true), u8Indices(2048), false,
                    new long[]{0L, 1L}, 0L, 2L, 2, 0);
            var offsetView = new LazyRleBoolArray(DType.BOOL, 2, bits(false, true), u8Indices(2048), false,
                    new long[]{0L, 1L}, 0L, 2L, 2, 1024);

            // When / Then
            assertThat(sut.getBoolean(1023)).isFalse();
            assertThat(sut.getBoolean(1024)).isTrue();
            assertThat(offsetView.getBoolean(0)).isTrue();
        }

        @Test
        void indexedClampAndEmptyChunk() {
            // Given an index slot past the chunk's value range → clamps to the last value; and
            // separately a chunk with no values at all → false, never an out-of-bounds read.
            var clamped = new LazyRleBoolArray(DType.BOOL, 2, bits(false, true), u8Indices(1024, 0, 9),
                    false, new long[]{0L}, 0L, 2L, 1, 0);
            var empty = new LazyRleBoolArray(DType.BOOL, 2, bits(), u8Indices(1024), false,
                    new long[]{0L}, 0L, 0L, 1, 0);

            // When
            var result = new ArrayList<Boolean>();
            empty.forEachBoolean(result::add);

            // Then
            assertThat(clamped.getBoolean(1)).isTrue();
            assertThat(empty.getBoolean(0)).isFalse();
            assertThat(result).containsExactly(false, false);
        }
    }

    /// An LSB-first bit-packed [BoolArray] value pool.
    private static BoolArray bits(boolean... values) {
        MemorySegment seg = Arena.ofAuto().allocate((values.length + 7) / 8 + 1);
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                long byteIdx = i >>> 3;
                byte current = seg.get(ValueLayout.JAVA_BYTE, byteIdx);
                seg.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (current | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, values.length, seg);
    }

    /// A `u8` index table of `slots` entries with the leading entries set; the remaining
    /// slots stay 0, matching the bits the writer leaves untouched for constant runs.
    private static MemorySegment u8Indices(int slots, int... leading) {
        MemorySegment seg = Arena.ofAuto().allocate(slots);
        for (int i = 0; i < leading.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) leading[i]);
        }
        return seg;
    }

    /// The `u16` counterpart of [#u8Indices(int, int...)] — FastLanes widens the index
    /// table once a chunk holds more runs than a byte can address.
    private static MemorySegment u16Indices(int slots, int... leading) {
        MemorySegment seg = Arena.ofAuto().allocate(slots * 2L);
        for (int i = 0; i < leading.length; i++) {
            seg.setAtIndex(VortexFormat.LE_SHORT, i, (short) leading[i]);
        }
        return seg;
    }

    private static MemorySegment bytes(int... values) {
        MemorySegment seg = Arena.ofAuto().allocate(values.length);
        for (int i = 0; i < values.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
        }
        return seg;
    }
}
