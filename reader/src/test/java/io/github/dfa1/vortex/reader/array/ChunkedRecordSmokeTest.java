package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkedRecordSmokeTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType I32 = new DType.Primitive(PType.I32, false);
    private static final DType F64 = new DType.Primitive(PType.F64, false);
    private static final DType F32 = new DType.Primitive(PType.F32, false);

    @Nested
    class FindChunk {

        @Test
        void zeroOnFirstChunk() {
            // Given
            long[] offsets = {0, 3, 7, 10};

            // When
            int c = ChunkedLongArray.findChunk(offsets, 0);

            // Then
            assertThat(c).isZero();
        }

        @Test
        void boundaryIndexLandsOnStartingChunk() {
            // Given offsets {0, 3, 7, 10}: row 3 = first row of chunk 1; row 7 = first row of chunk 2.
            long[] offsets = {0, 3, 7, 10};

            // When
            int atSecondBoundary = ChunkedLongArray.findChunk(offsets, 3);
            int atThirdBoundary = ChunkedLongArray.findChunk(offsets, 7);

            // Then
            assertThat(atSecondBoundary).isEqualTo(1);
            assertThat(atThirdBoundary).isEqualTo(2);
        }

        @Test
        void lastRowMapsToLastChunk() {
            // Given
            long[] offsets = {0, 3, 7, 10};

            // When
            int c = ChunkedLongArray.findChunk(offsets, 9);

            // Then
            assertThat(c).isEqualTo(2);
        }
    }

    @Nested
    class ChunkedLong {

        @Test
        void emptyChunkListRejected() {
            assertThatThrownBy(() -> ChunkedLongArray.of(I64, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void rowMismatchRejected() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray c0 = longChunk(arena, 1L, 2L, 3L);
                LongArray c1 = longChunk(arena, 4L, 5L);
                assertThatThrownBy(() -> ChunkedLongArray.of(I64, 99, List.of(c0, c1)))
                        .isInstanceOf(VortexException.class);
            }
        }

        @Test
        void getLongDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                // Given two chunks: [10,11,12] and [20,21,22,23]
                LongArray c0 = longChunk(arena, 10L, 11L, 12L);
                LongArray c1 = longChunk(arena, 20L, 21L, 22L, 23L);
                ChunkedLongArray sut = ChunkedLongArray.of(I64, 7, List.of(c0, c1));

                // When/Then
                assertThat(sut.getLong(0)).isEqualTo(10L);
                assertThat(sut.getLong(2)).isEqualTo(12L);
                assertThat(sut.getLong(3)).isEqualTo(20L);
                assertThat(sut.getLong(6)).isEqualTo(23L);
            }
        }

        @Test
        void nestedChunkedFlattens() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a nested ChunkedLongArray as one chunk
                LongArray leaf0 = longChunk(arena, 1L, 2L);
                LongArray leaf1 = longChunk(arena, 3L);
                ChunkedLongArray nested = ChunkedLongArray.of(I64, 3, List.of(leaf0, leaf1));
                LongArray leaf2 = longChunk(arena, 4L, 5L);

                // When
                ChunkedLongArray sut = ChunkedLongArray.of(I64, 5, List.of(nested, leaf2));

                // Then chunks were flattened — 3 children, not 2
                assertThat(sut.children()).hasSize(3);
                assertThat(sut.getLong(4)).isEqualTo(5L);
            }
        }

        @Test
        void foldIteratesChildren() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray c0 = longChunk(arena, 1L, 2L);
                LongArray c1 = longChunk(arena, 3L, 4L);
                ChunkedLongArray sut = ChunkedLongArray.of(I64, 4, List.of(c0, c1));

                long sum = sut.fold(0L, Long::sum);

                assertThat(sum).isEqualTo(10L);
            }
        }
    }

    @Nested
    class CrossTypePrimitives {

        @Test
        void chunkedDoubleSeesValues() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray c0 = doubleChunk(arena, 1.5, 2.5);
                DoubleArray c1 = doubleChunk(arena, 3.5);
                ChunkedDoubleArray sut = ChunkedDoubleArray.of(F64, 3, List.of(c0, c1));

                assertThat(sut.getDouble(2)).isEqualTo(3.5);
            }
        }

        @Test
        void chunkedIntSeesValues() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray c0 = intChunk(arena, 1, 2);
                IntArray c1 = intChunk(arena, 3, 4);
                ChunkedIntArray sut = ChunkedIntArray.of(I32, 4, List.of(c0, c1));

                assertThat(sut.getInt(3)).isEqualTo(4);
            }
        }

        @Test
        void chunkedFloatSeesValues() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray c0 = floatChunk(arena, 1.5f, 2.5f);
                FloatArray c1 = floatChunk(arena, 3.5f);
                ChunkedFloatArray sut = ChunkedFloatArray.of(F32, 3, List.of(c0, c1));

                assertThat(sut.getFloat(0)).isEqualTo(1.5f);
                assertThat(sut.getFloat(2)).isEqualTo(3.5f);
            }
        }

        @Test
        void wrongTypeRejected() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray longChunk = longChunk(arena, 1L);
                // Wrong: stuffing a LongArray into a Double container.
                assertThatThrownBy(() -> ChunkedDoubleArray.of(F64, 1, List.of(longChunk)))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class ChunkedShort {

        private static final DType I16 = new DType.Primitive(PType.I16, false);

        @Test
        void getShortDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                ShortArray c0 = shortChunk(arena, (short) 10, (short) 11);
                ShortArray c1 = shortChunk(arena, (short) 20, (short) 21);
                ChunkedShortArray sut = ChunkedShortArray.of(I16, 4, List.of(c0, c1));

                assertThat(sut.getShort(0)).isEqualTo((short) 10);
                assertThat(sut.getShort(2)).isEqualTo((short) 20);
                assertThat(sut.getShort(3)).isEqualTo((short) 21);
            }
        }

        @Test
        void getIntWidens() {
            try (Arena arena = Arena.ofConfined()) {
                ShortArray c0 = shortChunk(arena, (short) -1, (short) 2);
                ShortArray c1 = shortChunk(arena, (short) 3);
                ChunkedShortArray sut = ChunkedShortArray.of(I16, 3, List.of(c0, c1));

                // I16 is signed - sign-extends.
                assertThat(sut.getInt(0)).isEqualTo(-1);
                assertThat(sut.getInt(2)).isEqualTo(3);
            }
        }

        @Test
        void foldIteratesChildren() {
            try (Arena arena = Arena.ofConfined()) {
                ShortArray c0 = shortChunk(arena, (short) 1, (short) 2);
                ShortArray c1 = shortChunk(arena, (short) 3);
                ChunkedShortArray sut = ChunkedShortArray.of(I16, 3, List.of(c0, c1));

                long sum = sut.fold(0L, Long::sum);

                assertThat(sum).isEqualTo(6L);
            }
        }

        @Test
        void emptyRejected() {
            assertThatThrownBy(() -> ChunkedShortArray.of(I16, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class ChunkedByte {

        private static final DType I8 = new DType.Primitive(PType.I8, false);

        @Test
        void getByteDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                ByteArray c0 = byteChunk(arena, (byte) 1, (byte) 2);
                ByteArray c1 = byteChunk(arena, (byte) 3, (byte) 4);
                ChunkedByteArray sut = ChunkedByteArray.of(I8, 4, List.of(c0, c1));

                assertThat(sut.getByte(0)).isEqualTo((byte) 1);
                assertThat(sut.getByte(2)).isEqualTo((byte) 3);
                assertThat(sut.getByte(3)).isEqualTo((byte) 4);
            }
        }

        @Test
        void foldIteratesChildren() {
            try (Arena arena = Arena.ofConfined()) {
                ByteArray c0 = byteChunk(arena, (byte) 10, (byte) 20);
                ByteArray c1 = byteChunk(arena, (byte) 30);
                ChunkedByteArray sut = ChunkedByteArray.of(I8, 3, List.of(c0, c1));

                long sum = sut.fold(0L, Long::sum);

                assertThat(sum).isEqualTo(60L);
            }
        }

        @Test
        void rowMismatchRejected() {
            try (Arena arena = Arena.ofConfined()) {
                ByteArray c0 = byteChunk(arena, (byte) 1, (byte) 2);
                assertThatThrownBy(() -> ChunkedByteArray.of(I8, 99, List.of(c0)))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class ChunkedBool {

        private static final DType BOOL = new DType.Bool(false);

        @Test
        void getBooleanDispatchesAcrossChunks() {
            try (Arena arena = Arena.ofConfined()) {
                // chunk 0: true, false, true (3 bits, fits 1 byte)
                BoolArray c0 = boolChunk(arena, true, false, true);
                // chunk 1: false, true (2 bits)
                BoolArray c1 = boolChunk(arena, false, true);
                ChunkedBoolArray sut = ChunkedBoolArray.of(BOOL, 5, List.of(c0, c1));

                assertThat(sut.getBoolean(0)).isTrue();
                assertThat(sut.getBoolean(1)).isFalse();
                assertThat(sut.getBoolean(2)).isTrue();
                assertThat(sut.getBoolean(3)).isFalse();
                assertThat(sut.getBoolean(4)).isTrue();
            }
        }

        @Test
        void nestedFlattens() {
            try (Arena arena = Arena.ofConfined()) {
                BoolArray leaf0 = boolChunk(arena, true);
                BoolArray leaf1 = boolChunk(arena, false);
                ChunkedBoolArray nested = ChunkedBoolArray.of(BOOL, 2, List.of(leaf0, leaf1));
                BoolArray leaf2 = boolChunk(arena, true);

                ChunkedBoolArray sut = ChunkedBoolArray.of(BOOL, 3, List.of(nested, leaf2));

                assertThat(sut.children()).hasSize(3);
                assertThat(sut.getBoolean(2)).isTrue();
            }
        }

        @Test
        void emptyRejected() {
            assertThatThrownBy(() -> ChunkedBoolArray.of(BOOL, 0, List.of()))
                    .isInstanceOf(VortexException.class);
        }
    }

    private static LongArray longChunk(Arena arena, long... values) {
        MemorySegment seg = arena.allocate(values.length * 8L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, i, values[i]);
        }
        return new MaterializedLongArray(I64, values.length, seg.asReadOnly());
    }

    private static IntArray intChunk(Arena arena, int... values) {
        MemorySegment seg = arena.allocate(values.length * 4L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, values[i]);
        }
        return new MaterializedIntArray(I32, values.length, seg.asReadOnly());
    }

    private static DoubleArray doubleChunk(Arena arena, double... values) {
        MemorySegment seg = arena.allocate(values.length * 8L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, i, values[i]);
        }
        return new MaterializedDoubleArray(F64, values.length, seg.asReadOnly());
    }

    private static FloatArray floatChunk(Arena arena, float... values) {
        MemorySegment seg = arena.allocate(values.length * 4L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, i, values[i]);
        }
        return new MaterializedFloatArray(F32, values.length, seg.asReadOnly());
    }

    private static ShortArray shortChunk(Arena arena, short... values) {
        MemorySegment seg = arena.allocate(values.length * 2L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, i, values[i]);
        }
        return new MaterializedShortArray(new DType.Primitive(PType.I16, false), values.length, seg.asReadOnly());
    }

    private static ByteArray byteChunk(Arena arena, byte... values) {
        MemorySegment seg = arena.allocate(values.length);
        for (int i = 0; i < values.length; i++) {
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, values[i]);
        }
        return new MaterializedByteArray(new DType.Primitive(PType.I8, false), values.length, seg.asReadOnly());
    }

    private static BoolArray boolChunk(Arena arena, boolean... values) {
        int nBytes = (values.length + 7) / 8;
        MemorySegment seg = arena.allocate(nBytes);
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                int byteIdx = i >>> 3;
                int bitIdx = i & 7;
                byte cur = seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, byteIdx);
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << bitIdx)));
            }
        }
        return new MaterializedBoolArray(new DType.Bool(false), values.length, seg.asReadOnly());
    }
}
