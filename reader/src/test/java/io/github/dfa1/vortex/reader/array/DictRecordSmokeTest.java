package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.PTypeIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictRecordSmokeTest {

    private static final DType I64 = DType.I64;
    private static final DType I32 = DType.I32;
    private static final DType F64 = DType.F64;
    private static final DType F32 = DType.F32;
    private static final DType U8 = DType.U8;
    private static final DType U16 = DType.U16;
    private static final DType U32 = DType.U32;
    private static final DType U64 = DType.U64;

    @Nested
    class Validation {

        @Test
        void wrongCodesTypeRejected() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a values pool but codes typed as DoubleArray (a bug — codes must be narrow int)
                LongArray values = longArray(arena, 10L, 20L);
                DoubleArray badCodes = doubleArray(arena, 0.0, 1.0);

                // When/Then
                assertThatThrownBy(() -> DictLongArray.of(I64, 2, values, badCodes))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("codes");
            }
        }

        @Test
        void emptyAccepted() {
            // Given an empty codes ByteArray and an empty values pool.
            // We accept length == 0 — empty dict is a valid arrangement (no rows).
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena);
                ByteArray codes = byteArray(arena);

                DictLongArray sut = DictLongArray.of(I64, 0, values, codes);

                assertThat(sut.length()).isZero();
            }
        }

        @Test
        void lengthMismatchRejected() {
            // Given codes length 3 but caller claims length 5 — that would index past
            // the codes buffer and is a bug, so we fail fast at construction.
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 10L, 20L);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 0);

                assertThatThrownBy(() -> DictLongArray.of(I64, 5, values, codes))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("length");
            }
        }
    }

    @Nested
    class DictLong {

        @Test
        void u8CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                // Given a 3-value pool and U8 codes selecting them out of order
                LongArray values = longArray(arena, 100L, 200L, 300L);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 2, (byte) 1, (byte) 2);
                DictLongArray sut = DictLongArray.of(I64, 4, values, codes);

                // When/Then
                assertThat(sut.getLong(0)).isEqualTo(100L);
                assertThat(sut.getLong(1)).isEqualTo(300L);
                assertThat(sut.getLong(2)).isEqualTo(200L);
                assertThat(sut.getLong(3)).isEqualTo(300L);
            }
        }

        @Test
        void u16CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 7L, 8L, 9L);
                // ShortArray with U16 dtype — unsigned widening means code 0..N-1 reads cleanly.
                ShortArray codes = shortArray(arena, U16, (short) 2, (short) 1, (short) 0);
                DictLongArray sut = DictLongArray.of(I64, 3, values, codes);

                assertThat(sut.getLong(0)).isEqualTo(9L);
                assertThat(sut.getLong(1)).isEqualTo(8L);
                assertThat(sut.getLong(2)).isEqualTo(7L);
            }
        }

        @Test
        void u32CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 11L, 22L);
                IntArray codes = intArray(arena, U32, 1, 0, 1);
                DictLongArray sut = DictLongArray.of(I64, 3, values, codes);

                assertThat(sut.getLong(0)).isEqualTo(22L);
                assertThat(sut.getLong(2)).isEqualTo(22L);
            }
        }

        @Test
        void u64CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 5L, 6L);
                LongArray codes = longArray(arena, U64, 1L, 0L);
                DictLongArray sut = DictLongArray.of(I64, 2, values, codes);

                assertThat(sut.getLong(0)).isEqualTo(6L);
                assertThat(sut.getLong(1)).isEqualTo(5L);
            }
        }

        @Test
        void forEachLongMaterialisesInOrder() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 100L, 200L);
                ByteArray codes = byteArray(arena, (byte) 1, (byte) 0, (byte) 1);
                DictLongArray sut = DictLongArray.of(I64, 3, values, codes);

                var seen = new ArrayList<Long>();
                sut.forEachLong(seen::add);

                assertThat(seen).containsExactly(200L, 100L, 200L);
            }
        }

        @Test
        void foldSumsThroughCodes() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 1L, 2L);
                // codes = [0, 1, 1, 0] -> values = [1, 2, 2, 1] -> sum 6
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 1, (byte) 0);
                DictLongArray sut = DictLongArray.of(I64, 4, values, codes);

                long total = sut.fold(0L, Long::sum);

                assertThat(total).isEqualTo(6L);
            }
        }

        @Test
        void allCodeTypes_getForEachFoldMaterialize() {
            try (Arena arena = Arena.ofConfined()) {
                // Given — same selection [2,0,1] expressed as U8/U16/U32/U64 codes
                LongArray values = longArray(arena, 100L, 200L, 300L);
                long[] expected = {300L, 100L, 200L};
                List<Array> codeVariants = List.of(
                        byteArray(arena, (byte) 2, (byte) 0, (byte) 1),
                        shortArray(arena, U16, (short) 2, (short) 0, (short) 1),
                        intArray(arena, U32, 2, 0, 1),
                        longArray(arena, U64, 2L, 0L, 1L));

                for (Array codes : codeVariants) {
                    DictLongArray sut = DictLongArray.of(I64, 3, values, codes);
                    String label = codes.getClass().getSimpleName();

                    // getter
                    for (int i = 0; i < 3; i++) {
                        assertThat(sut.getLong(i)).as(label).isEqualTo(expected[i]);
                    }
                    // forEach
                    var seen = new ArrayList<Long>();
                    sut.forEachLong(seen::add);
                    assertThat(seen).as(label).containsExactly(300L, 100L, 200L);
                    // fold
                    assertThat(sut.fold(0L, Long::sum)).as(label).isEqualTo(600L);
                    // materialize
                    MemorySegment m = sut.materialize(arena);
                    for (int i = 0; i < 3; i++) {
                        assertThat(m.getAtIndex(PTypeIO.LE_LONG, i)).as(label).isEqualTo(expected[i]);
                    }
                }
            }
        }

        @Test
        void bulkOps_invalidCodesType_throw() {
            // Given — a record built directly (bypassing of()) with a non-int codes array
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 1L);
                DictLongArray sut = new DictLongArray(I64, 1, values, floatArray(arena, 0f));

                // When / Then — every bulk path hits the defensive default arm
                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class).hasMessageContaining("invalid codes");
                assertThatThrownBy(() -> sut.forEachLong(v -> { }))
                        .isInstanceOf(VortexException.class);
                assertThatThrownBy(() -> sut.fold(0L, Long::sum))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class DictInt {

        @Test
        void u8CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, I32, 10, 20, 30);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 2);
                DictIntArray sut = DictIntArray.of(I32, 2, values, codes);

                assertThat(sut.getInt(0)).isEqualTo(10);
                assertThat(sut.getInt(1)).isEqualTo(30);
            }
        }

        @Test
        void forEachIntMaterialisesInOrder() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, I32, 10, 20);
                ByteArray codes = byteArray(arena, (byte) 1, (byte) 0, (byte) 1);
                DictIntArray sut = DictIntArray.of(I32, 3, values, codes);

                var seen = new ArrayList<Integer>();
                sut.forEachInt(seen::add);

                assertThat(seen).containsExactly(20, 10, 20);
            }
        }

        @Test
        void foldThroughCodes() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, I32, 1, 5);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 1);
                DictIntArray sut = DictIntArray.of(I32, 3, values, codes);

                int total = sut.fold(0, Integer::sum);

                assertThat(total).isEqualTo(11);
            }
        }

        @Test
        void allCodeTypes_getForEachFoldMaterialize() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, I32, 100, 200, 300);
                int[] expected = {300, 100, 200};
                List<Array> codeVariants = List.of(
                        byteArray(arena, (byte) 2, (byte) 0, (byte) 1),
                        shortArray(arena, U16, (short) 2, (short) 0, (short) 1),
                        intArray(arena, U32, 2, 0, 1),
                        longArray(arena, U64, 2L, 0L, 1L));

                for (Array codes : codeVariants) {
                    DictIntArray sut = DictIntArray.of(I32, 3, values, codes);
                    String label = codes.getClass().getSimpleName();

                    for (int i = 0; i < 3; i++) {
                        assertThat(sut.getInt(i)).as(label).isEqualTo(expected[i]);
                    }
                    var seen = new ArrayList<Integer>();
                    sut.forEachInt(seen::add);
                    assertThat(seen).as(label).containsExactly(300, 100, 200);
                    assertThat(sut.fold(0, Integer::sum)).as(label).isEqualTo(600);
                    MemorySegment m = sut.materialize(arena);
                    for (int i = 0; i < 3; i++) {
                        assertThat(m.getAtIndex(PTypeIO.LE_INT, i)).as(label).isEqualTo(expected[i]);
                    }
                }
            }
        }

        @Test
        void bulkOps_invalidCodesType_throw() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, I32, 1);
                DictIntArray sut = new DictIntArray(I32, 1, values, floatArray(arena, 0f));

                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class).hasMessageContaining("invalid codes");
                assertThatThrownBy(() -> sut.forEachInt(v -> { }))
                        .isInstanceOf(VortexException.class);
                assertThatThrownBy(() -> sut.fold(0, Integer::sum))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class DictDouble {

        @Test
        void u8CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray values = doubleArray(arena, 1.5, 2.5, 3.5);
                ByteArray codes = byteArray(arena, (byte) 2, (byte) 0);
                DictDoubleArray sut = DictDoubleArray.of(F64, 2, values, codes);

                assertThat(sut.getDouble(0)).isEqualTo(3.5);
                assertThat(sut.getDouble(1)).isEqualTo(1.5);
            }
        }

        @Test
        void forEachDoubleMaterialisesInOrder() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray values = doubleArray(arena, 1.5, 2.5);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 0);
                DictDoubleArray sut = DictDoubleArray.of(F64, 3, values, codes);

                var seen = new ArrayList<Double>();
                sut.forEachDouble(seen::add);

                assertThat(seen).containsExactly(1.5, 2.5, 1.5);
            }
        }

        @Test
        void foldThroughCodes() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray values = doubleArray(arena, 1.0, 2.0);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 1);
                DictDoubleArray sut = DictDoubleArray.of(F64, 3, values, codes);

                double total = sut.fold(0.0, Double::sum);

                assertThat(total).isEqualTo(5.0);
            }
        }

        @Test
        void allCodeTypes_getForEachFoldMaterialize() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray values = doubleArray(arena, 1.5, 2.5, 3.5);
                double[] expected = {3.5, 1.5, 2.5};
                List<Array> codeVariants = List.of(
                        byteArray(arena, (byte) 2, (byte) 0, (byte) 1),
                        shortArray(arena, U16, (short) 2, (short) 0, (short) 1),
                        intArray(arena, U32, 2, 0, 1),
                        longArray(arena, U64, 2L, 0L, 1L));

                for (Array codes : codeVariants) {
                    DictDoubleArray sut = DictDoubleArray.of(F64, 3, values, codes);
                    String label = codes.getClass().getSimpleName();

                    for (int i = 0; i < 3; i++) {
                        assertThat(sut.getDouble(i)).as(label).isEqualTo(expected[i]);
                    }
                    var seen = new ArrayList<Double>();
                    sut.forEachDouble(seen::add);
                    assertThat(seen).as(label).containsExactly(3.5, 1.5, 2.5);
                    assertThat(sut.fold(0.0, Double::sum)).as(label).isEqualTo(7.5);
                    MemorySegment m = sut.materialize(arena);
                    for (int i = 0; i < 3; i++) {
                        assertThat(m.getAtIndex(PTypeIO.LE_DOUBLE, i)).as(label).isEqualTo(expected[i]);
                    }
                }
            }
        }

        @Test
        void bulkOps_invalidCodesType_throw() {
            try (Arena arena = Arena.ofConfined()) {
                DoubleArray values = doubleArray(arena, 1.0);
                DictDoubleArray sut = new DictDoubleArray(F64, 1, values, floatArray(arena, 0f));

                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class).hasMessageContaining("invalid codes");
                assertThatThrownBy(() -> sut.forEachDouble(v -> { }))
                        .isInstanceOf(VortexException.class);
                assertThatThrownBy(() -> sut.fold(0.0, Double::sum))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class DictFloat {

        @Test
        void u8CodesDispatch() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray values = floatArray(arena, 1.5f, 2.5f, 3.5f);
                ByteArray codes = byteArray(arena, (byte) 2, (byte) 0);
                DictFloatArray sut = DictFloatArray.of(F32, 2, values, codes);

                assertThat(sut.getFloat(0)).isEqualTo(3.5f);
                assertThat(sut.getFloat(1)).isEqualTo(1.5f);
            }
        }

        @Test
        void foldThroughCodes() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray values = floatArray(arena, 1.5f, 0.5f);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 0);
                DictFloatArray sut = DictFloatArray.of(F32, 3, values, codes);

                double total = sut.fold(0.0, Double::sum);

                assertThat(total).isEqualTo(3.5);
            }
        }

        @Test
        void allCodeTypes_getFoldMaterialize() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray values = floatArray(arena, 1.5f, 2.5f, 3.5f);
                float[] expected = {3.5f, 1.5f, 2.5f};
                List<Array> codeVariants = List.of(
                        byteArray(arena, (byte) 2, (byte) 0, (byte) 1),
                        shortArray(arena, U16, (short) 2, (short) 0, (short) 1),
                        intArray(arena, U32, 2, 0, 1),
                        longArray(arena, U64, 2L, 0L, 1L));

                for (Array codes : codeVariants) {
                    DictFloatArray sut = DictFloatArray.of(F32, 3, values, codes);
                    String label = codes.getClass().getSimpleName();

                    for (int i = 0; i < 3; i++) {
                        assertThat(sut.getFloat(i)).as(label).isEqualTo(expected[i]);
                    }
                    assertThat(sut.fold(0.0, Double::sum)).as(label).isEqualTo(7.5);
                    MemorySegment m = sut.materialize(arena);
                    for (int i = 0; i < 3; i++) {
                        assertThat(m.getAtIndex(PTypeIO.LE_FLOAT, i)).as(label).isEqualTo(expected[i]);
                    }
                }
            }
        }

        @Test
        void bulkOps_invalidCodesType_throw() {
            try (Arena arena = Arena.ofConfined()) {
                FloatArray values = floatArray(arena, 1.0f);
                DictFloatArray sut = new DictFloatArray(F32, 1, values, doubleArray(arena, 0.0));

                assertThatThrownBy(() -> sut.materialize(arena))
                        .isInstanceOf(VortexException.class).hasMessageContaining("invalid codes");
                assertThatThrownBy(() -> sut.fold(0.0, Double::sum))
                        .isInstanceOf(VortexException.class);
            }
        }
    }

    @Nested
    class MaskedValues {

        @Test
        void dictLongAcceptsNullableValuesViaWrapper() {
            // Given a dict with null cells expressed via a MaskedArray *around* the DictLongArray —
            // the validity mask sits at the row level, not inside the dict. This mirrors how the
            // layout decoder hands back a MaskedArray of (DictLongArray, validity).
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 42L, 99L);
                ByteArray codes = byteArray(arena, (byte) 0, (byte) 1, (byte) 0);
                DictLongArray dict = DictLongArray.of(I64, 3, values, codes);
                BoolArray validity = boolArray(arena, true, false, true);

                MaskedArray sut = new MaskedArray(dict, validity);

                // When/Then — the dict inner still resolves correctly; mask is separate.
                assertThat(((LongArray) sut.inner()).getLong(0)).isEqualTo(42L);
                assertThat(((LongArray) sut.inner()).getLong(2)).isEqualTo(42L);
                assertThat(sut.validity().getBoolean(1)).isFalse();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LongArray longArray(Arena arena, long... values) {
        return longArray(arena, I64, values);
    }

    private static LongArray longArray(Arena arena, DType dtype, long... values) {
        MemorySegment seg = arena.allocate(Math.max(values.length * 8L, 1L));
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, values[i]);
        }
        return new MaterializedLongArray(dtype, values.length, seg.asReadOnly());
    }

    private static IntArray intArray(Arena arena, DType dtype, int... values) {
        MemorySegment seg = arena.allocate(values.length * 4L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, values[i]);
        }
        return new MaterializedIntArray(dtype, values.length, seg.asReadOnly());
    }

    private static DoubleArray doubleArray(Arena arena, double... values) {
        MemorySegment seg = arena.allocate(values.length * 8L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_DOUBLE, i, values[i]);
        }
        return new MaterializedDoubleArray(F64, values.length, seg.asReadOnly());
    }

    private static FloatArray floatArray(Arena arena, float... values) {
        MemorySegment seg = arena.allocate(values.length * 4L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, values[i]);
        }
        return new MaterializedFloatArray(F32, values.length, seg.asReadOnly());
    }

    private static ShortArray shortArray(Arena arena, DType dtype, short... values) {
        MemorySegment seg = arena.allocate(values.length * 2L);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, values[i]);
        }
        return new MaterializedShortArray(dtype, values.length, seg.asReadOnly());
    }

    private static ByteArray byteArray(Arena arena, byte... values) {
        MemorySegment seg = arena.allocate(Math.max(values.length, 1));
        for (int i = 0; i < values.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, values[i]);
        }
        return new MaterializedByteArray(U8, values.length, seg.asReadOnly());
    }

    private static BoolArray boolArray(Arena arena, boolean... values) {
        int nBytes = (values.length + 7) / 8;
        MemorySegment seg = arena.allocate(Math.max(nBytes, 1));
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                int byteIdx = i >>> 3;
                int bitIdx = i & 7;
                byte cur = seg.get(ValueLayout.JAVA_BYTE, byteIdx);
                seg.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << bitIdx)));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, values.length, seg.asReadOnly());
    }
}
