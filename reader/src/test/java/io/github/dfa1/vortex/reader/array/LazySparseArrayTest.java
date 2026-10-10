package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.testing.TestSegments;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;

import static io.github.dfa1.vortex.core.testing.DTypes.BOOL;
import static io.github.dfa1.vortex.core.testing.DTypes.F16;
import static io.github.dfa1.vortex.core.testing.DTypes.F32;
import static io.github.dfa1.vortex.core.testing.DTypes.F64;
import static io.github.dfa1.vortex.core.testing.DTypes.I16;
import static io.github.dfa1.vortex.core.testing.DTypes.I32;
import static io.github.dfa1.vortex.core.testing.DTypes.I64;
import static io.github.dfa1.vortex.core.testing.DTypes.I8;
import static io.github.dfa1.vortex.core.testing.DTypes.U16;
import static io.github.dfa1.vortex.core.testing.DTypes.U8;
import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.floats;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for the lazy Sparse records. Covers fill vs patch dispatch, ordered
/// forEach iteration, fold reduction, and offset slicing semantics.
class LazySparseArrayTest {

    @Nested
    class Long {

        @Test
        void unpatchedPositionsReturnFill() {
            // Given — length=5, fill=99, patches at index 1 -> 7, index 3 -> 11
            LongArray values = longs(7L, 11L);
            Array indices = ints(1, 3);
            var sut = new LazySparseLongArray(I64, 5, 99L, values, indices, 0L);

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(99L);
            assertThat(sut.getLong(1)).isEqualTo(7L);
            assertThat(sut.getLong(2)).isEqualTo(99L);
            assertThat(sut.getLong(3)).isEqualTo(11L);
            assertThat(sut.getLong(4)).isEqualTo(99L);
        }

        @Test
        void forEachEmitsInOrder() {
            // Given
            LongArray values = longs(7L, 11L);
            Array indices = ints(1, 3);
            var sut = new LazySparseLongArray(I64, 5, 99L, values, indices, 0L);

            // When
            var seen = new ArrayList<java.lang.Long>();
            sut.forEachLong(seen::add);

            // Then
            assertThat(seen).containsExactly(99L, 7L, 99L, 11L, 99L);
        }

        @Test
        void foldSumsFillAndPatches() {
            // Given
            LongArray values = longs(7L, 11L);
            Array indices = ints(1, 3);
            var sut = new LazySparseLongArray(I64, 5, 99L, values, indices, 0L);

            // When
            long result = sut.fold(0L, java.lang.Long::sum);

            // Then — 99 + 7 + 99 + 11 + 99 = 315
            assertThat(result).isEqualTo(315L);
        }

        @Test
        void offsetSkipsLeadingPatches() {
            // Given — length=3 covering abs [4..7), fill=1, patches at abs 4 and 6
            LongArray values = longs(10L, 11L, 12L);
            Array indices = ints(1, 4, 6);
            var sut = new LazySparseLongArray(I64, 3, 1L, values, indices, 4L);

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(11L);
            assertThat(sut.getLong(1)).isEqualTo(1L);
            assertThat(sut.getLong(2)).isEqualTo(12L);
        }

        @Test
        void noPatchesIsAllFill() {
            // Given
            LongArray values = longs();
            Array indices = ints();
            var sut = new LazySparseLongArray(I64, 3, 42L, values, indices, 0L);

            // When
            var seen = new ArrayList<java.lang.Long>();
            sut.forEachLong(seen::add);

            // Then
            assertThat(seen).containsExactly(42L, 42L, 42L);
        }
    }

    @Nested
    class IntAndDouble {

        @Test
        void intPatchDispatches() {
            // Given
            IntArray values = ints(100, 200);
            Array indices = ints(0, 2);
            var sut = new LazySparseIntArray(I32, 3, 5, values, indices, 0L);

            // When / Then
            assertThat(sut.getInt(0)).isEqualTo(100);
            assertThat(sut.getInt(1)).isEqualTo(5);
            assertThat(sut.getInt(2)).isEqualTo(200);
        }

        @Test
        void doublePatchDispatches() {
            // Given
            DoubleArray values = doubles(1.5, 2.5);
            Array indices = ints(0, 2);
            var sut = new LazySparseDoubleArray(F64, 3, 0.0, values, indices, 0L);

            // When / Then
            assertThat(sut.getDouble(0)).isEqualTo(1.5);
            assertThat(sut.getDouble(1)).isEqualTo(0.0);
            assertThat(sut.getDouble(2)).isEqualTo(2.5);
        }
    }

    @Nested
    class ByteAndShort {

        // These exercise SparseArrays.patchedInt / foldInt (the shared int path the
        // byte/short sparse records delegate to) — distinct from the long/int/double
        // records above which fold over their own typed accessor.

        @Test
        void bytePatchAndFillDispatch() {
            // Given — patches at 0->7 and 2->11, fill 5
            ByteArray values = bytes((byte) 7, (byte) 11);
            Array indices = ints(0, 2);
            var sut = new LazySparseByteArray(I8, 3, (byte) 5, 5, values, indices, 0L);

            // When / Then
            assertThat(sut.getByte(0)).isEqualTo((byte) 7);
            assertThat(sut.getInt(1)).isEqualTo(5);
            assertThat(sut.getInt(2)).isEqualTo(11);
        }

        @Test
        void byteGetIntWidensUnsignedFill() {
            // Given — U8 fill 0xFF -> fillInt 255
            ByteArray values = bytes((byte) 1);
            Array indices = ints(0);
            var sut = new LazySparseByteArray(U8, 3, (byte) 0xFF, 255, values, indices, 0L);

            // When / Then — unpatched position reports 255 not -1
            assertThat(sut.getInt(1)).isEqualTo(255);
        }

        @Test
        void byteFoldSumsThroughIntPath() {
            // Given — length 5, fill 10, patches 1->7 and 3->11
            ByteArray values = bytes((byte) 7, (byte) 11);
            Array indices = ints(1, 3);
            var sut = new LazySparseByteArray(I8, 5, (byte) 10, 10, values, indices, 0L);

            // When / Then — 10+7+10+11+10 = 48
            assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(48L);
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
            // Given — length 5, fill 1, patches 1->100 and 3->200
            ShortArray values = shorts((short) 100, (short) 200);
            Array indices = ints(1, 3);
            var sut = new LazySparseShortArray(I16, 5, (short) 1, 1, values, indices, 0L);

            // When / Then — fill + patches; fold 1+100+1+200+1 = 303
            assertThat(sut.getInt(0)).isEqualTo(1);
            assertThat(sut.getInt(1)).isEqualTo(100);
            assertThat(sut.fold(0L, java.lang.Long::sum)).isEqualTo(303L);
        }

        @Test
        void shortGetIntWidensUnsigned() {
            // Given — widening flows through patchValues.getInt, so the patch array must be U16
            MemorySegment seg = Arena.ofAuto().allocate(2L, 2);
            seg.setAtIndex(ValueLayout.JAVA_SHORT, 0, (short) 0xFFFF);
            ShortArray values = new MaterializedShortArray(U16, 1, seg.asReadOnly());
            Array indices = ints(0);
            var sut = new LazySparseShortArray(U16, 2, (short) 0, 0, values, indices, 0L);

            // When / Then
            assertThat(sut.getInt(0)).isEqualTo(65535);
        }

        @Test
        void shortGetShortPatchFillAndNullPatches() {
            // Given — fill 1, patches at 1->100, 3->200
            ShortArray values = shorts((short) 100, (short) 200);
            Array indices = ints(1, 3);
            var sut = new LazySparseShortArray(I16, 5, (short) 1, 1, values, indices, 0L);

            // When / Then — getShort hits both patch (p>=0) and fill (p<0)
            assertThat(sut.getShort(0)).isEqualTo((short) 1);
            assertThat(sut.getShort(1)).isEqualTo((short) 100);
            assertThat(sut.getShort(3)).isEqualTo((short) 200);

            // null patches → every position returns the fill (getShort, getInt and fold paths)
            var nf = new LazySparseShortArray(I16, 3, (short) 7, 7, null, null, 0L);
            assertThat(nf.getShort(0)).isEqualTo((short) 7);
            assertThat(nf.getInt(0)).isEqualTo(7);
            assertThat(nf.fold(0L, java.lang.Long::sum)).isEqualTo(21L);
        }
    }

    @Nested
    class SequentialNarrowWalk {

        // Byte/short sparse arrays used to inherit the per-row forEach default, a patch binary
        // search per row; a dict over sparse codes materialized through it. These pin that the
        // sequential walk emits exactly the per-row values, including across the slice offset.

        @Test
        void forEachShort_withOffset_emitsFillAndPatchesInOrder() {
            // Given patches at absolute 0, 2, 4 and offset 1: logical rows are absolute 1..4
            ShortArray values = shorts((short) 10, (short) 20, (short) 30);
            var sut = new LazySparseShortArray(I16, 4, (short) 1, 1, values, ints(0, 2, 4), 1L);
            var result = new ArrayList<Short>();

            // When
            sut.forEachShort(result::add);

            // Then
            assertThat(result).containsExactly((short) 1, (short) 20, (short) 1, (short) 30);
        }

        @Test
        void forEachByte_withOffset_emitsFillAndPatchesInOrder() {
            // Given
            ByteArray values = bytes((byte) 10, (byte) 20, (byte) 30);
            var sut = new LazySparseByteArray(I8, 4, (byte) 1, 1, values, ints(0, 2, 4), 1L);
            var result = new ArrayList<Byte>();

            // When
            sut.forEachByte(result::add);

            // Then
            assertThat(result).containsExactly((byte) 1, (byte) 20, (byte) 1, (byte) 30);
        }

        @Test
        void forEachShort_unsortedPatchIndices_throwsVortexException() {
            // Given a crafted file whose patch indices go backwards
            ShortArray values = shorts((short) 10, (short) 20);
            var sut = new LazySparseShortArray(I16, 4, (short) 1, 1, values, ints(2, 1), 0L);

            // When / Then: the walk's ordering guard, not an IndexOutOfBoundsException downstream
            assertThatThrownBy(() -> sut.forEachShort(v -> { }))
                    .isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Bool {

        @Test
        void getBooleanPatchAndFill() {
            // Given — fill false, single patch true at index 2
            BoolArray values = bools(true);
            Array indices = ints(2);
            var sut = new LazySparseBoolArray(BOOL, 4, false, values, indices, 0L);

            // When / Then
            assertThat(sut.getBoolean(0)).isFalse(); // fill (p<0)
            assertThat(sut.getBoolean(2)).isTrue();  // patch
        }

        @Test
        void forEachBooleanEmitsFillAndPatches() {
            // Given
            BoolArray values = bools(true);
            Array indices = ints(2);
            var sut = new LazySparseBoolArray(BOOL, 4, false, values, indices, 0L);

            // When
            var seen = new ArrayList<Boolean>();
            sut.forEachBoolean(seen::add);

            // Then
            assertThat(seen).containsExactly(false, false, true, false);
        }
    }

    @Nested
    class Float16 {

        private static final short ONE_AND_A_HALF = (short) 0x3E00;
        private static final short TWO_AND_A_HALF = (short) 0x4100;
        private static final short NINE = (short) 0x4880;
        private static final short NAN_PAYLOAD = (short) 0x7C01;

        @Test
        void patchAndFillDispatch() {
            // Given
            Float16Array values = float16s(ONE_AND_A_HALF, TWO_AND_A_HALF);
            var sut = new LazySparseFloat16Array(F16, 3, NINE, values, ints(0, 2), 0L);

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(1.5f);
            assertThat(sut.getFloat(1)).isEqualTo(9.0f);
            assertThat(sut.getFloat(2)).isEqualTo(2.5f);
        }

        @Test
        void materialize_keepsFillAndPatchBitsAndHonorsOffset() {
            // Given length 3 covering abs [4..7): fill is a NaN payload, patches at abs 4 and 6
            Float16Array values = float16s(ONE_AND_A_HALF, TWO_AND_A_HALF, NINE);
            var sut = new LazySparseFloat16Array(F16, 3, NAN_PAYLOAD, values, ints(1, 4, 6), 4L);

            // When
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = sut.materialize(arena);

                // Then bits, not widened floats: the payload would not survive a round trip
                assertThat(result.byteSize()).isEqualTo(6L);
                assertThat(result.getAtIndex(VortexFormat.LE_SHORT, 0)).isEqualTo(TWO_AND_A_HALF);
                assertThat(result.getAtIndex(VortexFormat.LE_SHORT, 1)).isEqualTo(NAN_PAYLOAD);
                assertThat(result.getAtIndex(VortexFormat.LE_SHORT, 2)).isEqualTo(NINE);
            }
        }

        @Test
        void nullPatchesIsAllFill() {
            // Given patchValues == null, the no-patch fast path
            var sut = new LazySparseFloat16Array(F16, 3, NINE, null, null, 0L);

            // When / Then
            assertThat(sut.getFloat(2)).isEqualTo(9.0f);
            try (Arena arena = Arena.ofConfined()) {
                assertThat(sut.materialize(arena).getAtIndex(VortexFormat.LE_SHORT, 1)).isEqualTo(NINE);
            }
        }

        @Test
        void limited_cutsTheRowCountOnly() {
            // Given
            Float16Array values = float16s(ONE_AND_A_HALF);
            var sut = new LazySparseFloat16Array(F16, 5, NINE, values, ints(1), 0L);

            // When
            Array result = sut.limited(2);

            // Then
            assertThat(result.length()).isEqualTo(2L);
            assertThat(((Float16Array) result).getFloat(1)).isEqualTo(1.5f);
            assertThat(sut.limited(5)).isSameAs(sut);
        }

        private static Float16Array float16s(short... bits) {
            return new MaterializedFloat16Array(F16, bits.length, TestSegments.leShorts(bits));
        }
    }

    @Nested
    class Float {

        @Test
        void patchAndFillDispatch() {
            // Given
            FloatArray values = floats(1.5f, 2.5f);
            Array indices = ints(0, 2);
            var sut = new LazySparseFloatArray(F32, 3, 9.0f, values, indices, 0L);

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(1.5f);
            assertThat(sut.getFloat(1)).isEqualTo(9.0f);
            assertThat(sut.getFloat(2)).isEqualTo(2.5f);
        }

        @Test
        void foldSumsFillAndPatches() {
            // Given — length=5, fill=10, patches at index 1 and 3
            FloatArray values = floats(1.5f, 2.5f);
            Array indices = ints(1, 3);
            var sut = new LazySparseFloatArray(F32, 5, 10.0f, values, indices, 0L);

            // When
            double result = sut.fold(0.0, java.lang.Double::sum);

            // Then — 10 + 1.5 + 10 + 2.5 + 10 = 34
            assertThat(result).isEqualTo(34.0);
        }

        @Test
        void offsetSkipsLeadingPatches() {
            // Given — length=3 covering abs [4..7), fill=1, patches at abs 4 and 6
            FloatArray values = floats(10.0f, 11.0f, 12.0f);
            Array indices = ints(1, 4, 6);
            var sut = new LazySparseFloatArray(F32, 3, 1.0f, values, indices, 4L);

            // When / Then
            assertThat(sut.getFloat(0)).isEqualTo(11.0f);
            assertThat(sut.getFloat(1)).isEqualTo(1.0f);
            assertThat(sut.getFloat(2)).isEqualTo(12.0f);
        }

        @Test
        void nullPatchesIsAllFill() {
            // Given — patchValues == null is the no-patch fast path
            var sut = new LazySparseFloatArray(F32, 3, 42.0f, null, null, 0L);

            // When / Then — every position returns the fill
            assertThat(sut.getFloat(0)).isEqualTo(42.0f);
            assertThat(sut.fold(0.0, java.lang.Double::sum)).isEqualTo(126.0);
        }
    }

    /// Malformed-input cases (TODO.md §Security, ADR 0003): `patchIndices` is untrusted file
    /// data the format requires to be sorted ascending. `walkPatches` (the shared `forEach`
    /// engine) advances its cursor to `patchAbs + 1` per patch and assumes it never sees a
    /// smaller value again — before the guard landed, an out-of-order index moved the cursor
    /// backwards and the walk re-covered already-emitted positions, emitting more callbacks
    /// than the array's own `length()`. A caller sizing a buffer from `length()` (as every real
    /// `forEach*` caller does) then overran it with a raw `IndexOutOfBoundsException`.
    @Nested
    class AdversarialInput {

        @Test
        void forEachLong_unsortedPatchIndices_throws() {
            // Given — length=10, patch indices out of order (5 before 1)
            LongArray values = longs(50L, 10L);
            Array indices = ints(5, 1);
            var sut = new LazySparseLongArray(I64, 10, 0L, values, indices, 0L);

            // When / Then
            assertThatThrownBy(() -> sut.forEachLong(v -> { }))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not sorted");
        }

        @Test
        void fold_unsortedPatchIndices_throws() {
            // Given — same out-of-order indices, reached through the fold walker instead
            LongArray values = longs(50L, 10L);
            Array indices = ints(5, 1);
            var sut = new LazySparseLongArray(I64, 10, 0L, values, indices, 0L);

            // When / Then
            assertThatThrownBy(() -> sut.fold(0L, java.lang.Long::sum))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not sorted");
        }
    }
}
