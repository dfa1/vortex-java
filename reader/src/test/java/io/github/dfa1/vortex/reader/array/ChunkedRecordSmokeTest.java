package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static io.github.dfa1.vortex.core.testing.DTypes.BOOL;
import static io.github.dfa1.vortex.core.testing.DTypes.F32;
import static io.github.dfa1.vortex.core.testing.DTypes.F64;
import static io.github.dfa1.vortex.core.testing.DTypes.I16;
import static io.github.dfa1.vortex.core.testing.DTypes.I32;
import static io.github.dfa1.vortex.core.testing.DTypes.I64;
import static io.github.dfa1.vortex.core.testing.DTypes.I8;
import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.floats;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkedRecordSmokeTest {

    @Nested
    class FindChunk {

        @Test
        void zeroOnFirstChunk() {
            // Given
            long[] offsets = {0, 3, 7, 10};

            // When
            int result = ChunkedLongArray.findChunk(offsets, 0);

            // Then
            assertThat(result).isZero();
        }

        @Test
        void boundaryIndexLandsOnStartingChunk() {
            // Given offsets {0, 3, 7, 10}: row 3 = first row of chunk 1; row 7 = first row of chunk 2.
            long[] offsets = {0, 3, 7, 10};

            // When
            int resultSecondBoundary = ChunkedLongArray.findChunk(offsets, 3);
            int resultThirdBoundary = ChunkedLongArray.findChunk(offsets, 7);

            // Then
            assertThat(resultSecondBoundary).isEqualTo(1);
            assertThat(resultThirdBoundary).isEqualTo(2);
        }

        @Test
        void lastRowMapsToLastChunk() {
            // Given
            long[] offsets = {0, 3, 7, 10};

            // When
            int result = ChunkedLongArray.findChunk(offsets, 9);

            // Then
            assertThat(result).isEqualTo(2);
        }
    }

    @Nested
    class ChunkedLong {

        @Test
        void emptyChunkListRejected() {
            // Given / When / Then
            assertThatThrownBy(() -> ChunkedLongArray.of(I64, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void rowMismatchRejected() {
            // Given
            LongArray c0 = longs(1L, 2L, 3L);
            LongArray c1 = longs(4L, 5L);
            List<LongArray> chunks = List.of(c0, c1);

            // When / Then
            assertThatThrownBy(() -> ChunkedLongArray.of(I64, 99, chunks))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void getLongDispatchesAcrossChunks() {
            // Given two chunks: [10,11,12] and [20,21,22,23]
            LongArray c0 = longs(10L, 11L, 12L);
            LongArray c1 = longs(20L, 21L, 22L, 23L);
            ChunkedLongArray sut = ChunkedLongArray.of(I64, 7, List.of(c0, c1));

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(10L);
            assertThat(sut.getLong(2)).isEqualTo(12L);
            assertThat(sut.getLong(3)).isEqualTo(20L);
            assertThat(sut.getLong(6)).isEqualTo(23L);
        }

        @Test
        void nestedChunkedFlattens() {
            // Given a nested ChunkedLongArray as one chunk
            LongArray leaf0 = longs(1L, 2L);
            LongArray leaf1 = longs(3L);
            ChunkedLongArray nested = ChunkedLongArray.of(I64, 3, List.of(leaf0, leaf1));
            LongArray leaf2 = longs(4L, 5L);

            // When
            ChunkedLongArray sut = ChunkedLongArray.of(I64, 5, List.of(nested, leaf2));

            // Then chunks were flattened — 3 children, not 2
            assertThat(sut.children()).hasSize(3);
            assertThat(sut.getLong(4)).isEqualTo(5L);
        }

        @Test
        void foldIteratesChildren() {
            // Given
            LongArray c0 = longs(1L, 2L);
            LongArray c1 = longs(3L, 4L);
            ChunkedLongArray sut = ChunkedLongArray.of(I64, 4, List.of(c0, c1));

            // When
            long result = sut.fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(10L);
        }
    }

    @Nested
    class CrossTypePrimitives {

        @Test
        void chunkedDoubleSeesValues() {
            // Given
            DoubleArray c0 = doubles(1.5, 2.5);
            DoubleArray c1 = doubles(3.5);
            ChunkedDoubleArray sut = ChunkedDoubleArray.of(F64, 3, List.of(c0, c1));

            // When / Then
            assertThat(sut.getDouble(2)).isEqualTo(3.5);
        }

        @Test
        void chunkedIntSeesValues() {
            // Given
            IntArray c0 = ints(1, 2);
            IntArray c1 = ints(3, 4);
            ChunkedIntArray sut = ChunkedIntArray.of(I32, 4, List.of(c0, c1));

            // When / Then
            assertThat(sut.getInt(3)).isEqualTo(4);
        }

        @Test
        void chunkedFloatSeesValues() {
            // Given
            FloatArray c0 = floats(1.5f, 2.5f);
            FloatArray c1 = floats(3.5f);
            ChunkedFloatArray sut = ChunkedFloatArray.of(F32, 3, List.of(c0, c1));

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(1.5f);
            assertThat(sut.getFloat(2)).isEqualTo(3.5f);
        }

        @Test
        void wrongTypeRejected() {
            // Given — stuffing a LongArray into a Double container is a bug
            LongArray longChunk = longs(1L);
            List<LongArray> chunks = List.of(longChunk);

            // When / Then
            assertThatThrownBy(() -> ChunkedDoubleArray.of(F64, 1, chunks))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class ChunkedShort {

        @Test
        void getShortDispatchesAcrossChunks() {
            // Given
            ShortArray c0 = shorts((short) 10, (short) 11);
            ShortArray c1 = shorts((short) 20, (short) 21);
            ChunkedShortArray sut = ChunkedShortArray.of(I16, 4, List.of(c0, c1));

            // When / Then
            assertThat(sut.getShort(0)).isEqualTo((short) 10);
            assertThat(sut.getShort(2)).isEqualTo((short) 20);
            assertThat(sut.getShort(3)).isEqualTo((short) 21);
        }

        @Test
        void getIntWidens() {
            // Given
            ShortArray c0 = shorts((short) -1, (short) 2);
            ShortArray c1 = shorts((short) 3);
            ChunkedShortArray sut = ChunkedShortArray.of(I16, 3, List.of(c0, c1));

            // When / Then — I16 is signed, so sign-extends
            assertThat(sut.getInt(0)).isEqualTo(-1);
            assertThat(sut.getInt(2)).isEqualTo(3);
        }

        @Test
        void foldIteratesChildren() {
            // Given
            ShortArray c0 = shorts((short) 1, (short) 2);
            ShortArray c1 = shorts((short) 3);
            ChunkedShortArray sut = ChunkedShortArray.of(I16, 3, List.of(c0, c1));

            // When
            long result = sut.fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(6L);
        }

        @Test
        void emptyRejected() {
            // Given / When / Then
            assertThatThrownBy(() -> ChunkedShortArray.of(I16, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void forEachShortIteratesChildren() {
            // Given
            ShortArray c0 = shorts((short) 1, (short) 2);
            ShortArray c1 = shorts((short) 3);
            ChunkedShortArray sut = ChunkedShortArray.of(I16, 3, List.of(c0, c1));

            // When
            var seen = new ArrayList<Short>();
            sut.forEachShort(seen::add);

            // Then
            assertThat(seen).containsExactly((short) 1, (short) 2, (short) 3);
        }
    }

    @Nested
    class ChunkedByte {

        @Test
        void getByteDispatchesAcrossChunks() {
            // Given
            ByteArray c0 = bytes((byte) 1, (byte) 2);
            ByteArray c1 = bytes((byte) 3, (byte) 4);
            ChunkedByteArray sut = ChunkedByteArray.of(I8, 4, List.of(c0, c1));

            // When / Then
            assertThat(sut.getByte(0)).isEqualTo((byte) 1);
            assertThat(sut.getByte(2)).isEqualTo((byte) 3);
            assertThat(sut.getByte(3)).isEqualTo((byte) 4);
        }

        @Test
        void foldIteratesChildren() {
            // Given
            ByteArray c0 = bytes((byte) 10, (byte) 20);
            ByteArray c1 = bytes((byte) 30);
            ChunkedByteArray sut = ChunkedByteArray.of(I8, 3, List.of(c0, c1));

            // When
            long result = sut.fold(0L, Long::sum);

            // Then
            assertThat(result).isEqualTo(60L);
        }

        @Test
        void rowMismatchRejected() {
            // Given
            ByteArray c0 = bytes((byte) 1, (byte) 2);
            List<ByteArray> chunks = List.of(c0);

            // When / Then
            assertThatThrownBy(() -> ChunkedByteArray.of(I8, 99, chunks))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void forEachByteIteratesChildren() {
            // Given
            ByteArray c0 = bytes((byte) 10, (byte) 20);
            ByteArray c1 = bytes((byte) 30);
            ChunkedByteArray sut = ChunkedByteArray.of(I8, 3, List.of(c0, c1));

            // When
            var seen = new ArrayList<Byte>();
            sut.forEachByte(seen::add);

            // Then
            assertThat(seen).containsExactly((byte) 10, (byte) 20, (byte) 30);
        }
    }

    @Nested
    class ChunkedBool {

        @Test
        void getBooleanDispatchesAcrossChunks() {
            // Given chunk 0: true,false,true (3 bits); chunk 1: false,true (2 bits)
            BoolArray c0 = bools(true, false, true);
            BoolArray c1 = bools(false, true);
            ChunkedBoolArray sut = ChunkedBoolArray.of(BOOL, 5, List.of(c0, c1));

            // When / Then
            assertThat(sut.getBoolean(0)).isTrue();
            assertThat(sut.getBoolean(1)).isFalse();
            assertThat(sut.getBoolean(2)).isTrue();
            assertThat(sut.getBoolean(3)).isFalse();
            assertThat(sut.getBoolean(4)).isTrue();
        }

        @Test
        void nestedFlattens() {
            // Given
            BoolArray leaf0 = bools(true);
            BoolArray leaf1 = bools(false);
            ChunkedBoolArray nested = ChunkedBoolArray.of(BOOL, 2, List.of(leaf0, leaf1));
            BoolArray leaf2 = bools(true);

            // When
            ChunkedBoolArray sut = ChunkedBoolArray.of(BOOL, 3, List.of(nested, leaf2));

            // Then
            assertThat(sut.children()).hasSize(3);
            assertThat(sut.getBoolean(2)).isTrue();
        }

        @Test
        void emptyRejected() {
            // Given / When / Then
            assertThatThrownBy(() -> ChunkedBoolArray.of(BOOL, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void forEachBooleanIteratesChildren() {
            // Given
            BoolArray c0 = bools(true, false);
            BoolArray c1 = bools(true);
            ChunkedBoolArray sut = ChunkedBoolArray.of(BOOL, 3, List.of(c0, c1));

            // When
            var seen = new ArrayList<Boolean>();
            sut.forEachBoolean(seen::add);

            // Then
            assertThat(seen).containsExactly(true, false, true);
        }
    }

    @Nested
    class ChunkedIntFull {

        @Test
        void forEachFoldAndMaterialize() {
            // Given
            ChunkedIntArray sut = ChunkedIntArray.of(I32, 4, List.of(ints(1, 2), ints(3, 4)));

            // When / Then
            var seen = new ArrayList<Integer>();
            sut.forEachInt(seen::add);
            assertThat(seen).containsExactly(1, 2, 3, 4);
            assertThat(sut.fold(0, Integer::sum)).isEqualTo(10);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = sut.materialize(arena);
                for (int i = 0; i < 4; i++) {
                    assertThat(m.getAtIndex(VortexFormat.LE_INT, i)).isEqualTo(sut.getInt(i));
                }
            }
        }

        @Test
        void limitedKeepsLimitsAndBreaks() {
            // Given 3 chunks of 2 rows each; limit 3 keeps chunk0, limits chunk1 to 1, breaks before chunk2
            ChunkedIntArray sut = ChunkedIntArray.of(I32, 6, List.of(ints(1, 2), ints(3, 4), ints(5, 6)));

            // When
            IntArray limited = (IntArray) sut.limited(3);

            // Then
            assertThat(limited.length()).isEqualTo(3);
            assertThat(limited.getInt(0)).isEqualTo(1);
            assertThat(limited.getInt(2)).isEqualTo(3);
        }

        @Test
        void maskedChunkFlattensToInner() {
            // Given a masked chunk wrapping an IntArray
            MaskedArray masked = new MaskedArray(ints(7, 8), bools(true, true));

            // When
            ChunkedIntArray sut = ChunkedIntArray.of(I32, 2, List.of(masked));

            // Then — flattened to the inner IntArray
            assertThat(sut.getInt(1)).isEqualTo(8);
        }

        @Test
        void wrongChunkTypeRejected() {
            // Given — a LongArray is not an IntArray chunk
            List<LongArray> chunks = List.of(longs(1L));

            // When / Then
            assertThatThrownBy(() -> ChunkedIntArray.of(I32, 1, chunks))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void ofFlattensNestedAndValidates() {
            // nested flatten
            ChunkedIntArray nested = ChunkedIntArray.of(I32, 3, List.of(ints(1, 2), ints(3)));
            ChunkedIntArray sut = ChunkedIntArray.of(I32, 5, List.of(nested, ints(4, 5)));
            assertThat(sut.children()).hasSize(3);
            assertThat(sut.getInt(4)).isEqualTo(5);

            // empty + row mismatch
            List<IntArray> oneChunk = List.of(ints(1));
            assertThatThrownBy(() -> ChunkedIntArray.of(I32, 0, List.of()))
                    .isInstanceOf(VortexException.class);
            assertThatThrownBy(() -> ChunkedIntArray.of(I32, 99, oneChunk))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class ChunkedDoubleFull {

        @Test
        void forEachFoldAndMaterialize() {
            // Given
            ChunkedDoubleArray sut = ChunkedDoubleArray.of(F64, 3, List.of(doubles(1.5, 2.5), doubles(3.5)));

            // When / Then
            var seen = new ArrayList<Double>();
            sut.forEachDouble(seen::add);
            assertThat(seen).containsExactly(1.5, 2.5, 3.5);
            assertThat(sut.fold(0.0, Double::sum)).isEqualTo(7.5);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = sut.materialize(arena);
                for (int i = 0; i < 3; i++) {
                    assertThat(m.getAtIndex(VortexFormat.LE_DOUBLE, i)).isEqualTo(sut.getDouble(i));
                }
            }
        }

        @Test
        void limitedKeepsLimitsAndBreaks() {
            ChunkedDoubleArray sut = ChunkedDoubleArray.of(F64, 6,
                    List.of(doubles(1, 2), doubles(3, 4), doubles(5, 6)));

            DoubleArray limited = (DoubleArray) sut.limited(3);

            assertThat(limited.length()).isEqualTo(3);
            assertThat(limited.getDouble(2)).isEqualTo(3.0);
        }

        @Test
        void ofFlattensNestedAndValidatesMismatch() {
            ChunkedDoubleArray nested = ChunkedDoubleArray.of(F64, 3, List.of(doubles(1, 2), doubles(3)));
            ChunkedDoubleArray sut = ChunkedDoubleArray.of(F64, 5, List.of(nested, doubles(4, 5)));
            assertThat(sut.children()).hasSize(3);

            List<DoubleArray> oneChunk = List.of(doubles(1));
            assertThatThrownBy(() -> ChunkedDoubleArray.of(F64, 99, oneChunk))
                    .isInstanceOf(VortexException.class);

            // masked chunk flattens to its inner DoubleArray
            var masked = ChunkedDoubleArray.of(F64, 2,
                    List.of(new MaskedArray(doubles(8.0, 9.0), bools(true, true))));
            assertThat(masked.getDouble(1)).isEqualTo(9.0);
        }

        @Test
        void emptyRejected() {
            assertThatThrownBy(() -> ChunkedDoubleArray.of(F64, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class ChunkedFloatFull {

        @Test
        void foldAndMaterialize() {
            // Given
            ChunkedFloatArray sut = ChunkedFloatArray.of(F32, 3, List.of(floats(1.5f, 2.5f), floats(3.5f)));

            // When / Then
            assertThat(sut.fold(0.0, Double::sum)).isEqualTo(7.5);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = sut.materialize(arena);
                for (int i = 0; i < 3; i++) {
                    assertThat(m.getAtIndex(VortexFormat.LE_FLOAT, i)).isEqualTo(sut.getFloat(i));
                }
            }
        }

        @Test
        void limitedKeepsLimitsAndBreaks() {
            ChunkedFloatArray sut = ChunkedFloatArray.of(F32, 6,
                    List.of(floats(1, 2), floats(3, 4), floats(5, 6)));

            FloatArray limited = (FloatArray) sut.limited(3);

            assertThat(limited.length()).isEqualTo(3);
            assertThat(limited.getFloat(2)).isEqualTo(3.0f);
        }

        @Test
        void ofFlattensNestedValidatesAndRejectsWrongType() {
            ChunkedFloatArray nested = ChunkedFloatArray.of(F32, 3, List.of(floats(1, 2), floats(3)));
            ChunkedFloatArray sut = ChunkedFloatArray.of(F32, 5, List.of(nested, floats(4, 5)));
            assertThat(sut.children()).hasSize(3);

            List<FloatArray> oneFloatChunk = List.of(floats(1));
            List<LongArray> oneLongChunk = List.of(longs(1L));
            assertThatThrownBy(() -> ChunkedFloatArray.of(F32, 99, oneFloatChunk))
                    .isInstanceOf(VortexException.class);
            assertThatThrownBy(() -> ChunkedFloatArray.of(F32, 1, oneLongChunk))
                    .isInstanceOf(VortexException.class);

            // masked chunk flattens to its inner FloatArray
            var masked = ChunkedFloatArray.of(F32, 2,
                    List.of(new MaskedArray(floats(8.0f, 9.0f), bools(true, true))));
            assertThat(masked.getFloat(1)).isEqualTo(9.0f);
        }

        @Test
        void emptyRejected() {
            assertThatThrownBy(() -> ChunkedFloatArray.of(F32, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }
    }
}
