package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.util.OptionalLong;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimdOperationsTest {

    // Full-range values: a wrong width, or sign vs zero extension, changes the result
    private static final long[] VALUES = {0L, -1L, 1L, 0x80L, 0xFFL, 0x8000L, 0xFFFFL, 0x80000000L,
            0xFFFFFFFFL, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEFL};

    private final SimdOperations sut = SimdOperationsSupport.preferred();

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void widenInto_roundTripsNarrowedValues(PType ptype) {
        // Given values narrowed to ptype's width with the PTypeIO reference writer
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(VALUES.length * ptype.byteSize());
            long[] expected = new long[VALUES.length];
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(src, i * ptype.byteSize(), ptype, VALUES[i]);
                expected[i] = PrimitiveArrays.readLong(src, i * ptype.byteSize(), ptype, EncodingId.VORTEX_PRIMITIVE);
            }

            // When widened from element 2, leaving the rest of out untouched
            long[] result = new long[VALUES.length];
            sut.widenInto(src, 2, VALUES.length - 2, ptype, result);

            // Then each element is sign- or zero-extended exactly as the reference read does
            for (int i = 0; i < VALUES.length - 2; i++) {
                assertThat(result[i]).isEqualTo(expected[i + 2]);
            }
            assertThat(result[VALUES.length - 1]).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void allEqual_integerArrays(PType ptype) {
        // Given lengths around the 256-element block edge, where a block-boundary slip would hide a mismatch
        for (int length : new int[]{1, 2, 255, 256, 257, 600}) {
            Object constant = constantArray(ptype, length);

            // When / Then a constant array is equal, and so is the empty one
            assertThat(sut.allEqual(constant, ptype)).isTrue();

            // And changing any single element, first or last of a block included, breaks it
            for (int position : new int[]{0, length / 2, length - 1, Math.min(256, length - 1)}) {
                Object changed = constantArray(ptype, length);
                bump(changed, position);
                assertThat(sut.allEqual(changed, ptype)).as("%s[%d] differs at %d", ptype, length, position)
                        .isEqualTo(length == 1);
            }
        }
        assertThat(sut.allEqual(constantArray(ptype, 0), ptype)).isTrue();
    }

    @Test
    void allEqual_floatsCompareRawBits() {
        // Given arrays equal under ==/Double.equals-ish rules but not bit for bit
        float[] zeros = {0.0f, -0.0f};
        double[] nans = {Double.longBitsToDouble(0x7ff8000000000000L), Double.longBitsToDouble(0x7ff8000000000001L)};
        float[] sameNan = {Float.NaN, Float.NaN};

        // When / Then
        assertThat(sut.allEqual(zeros, PType.F32)).isFalse();
        assertThat(sut.allEqual(nans, PType.F64)).isFalse();
        assertThat(sut.allEqual(sameNan, PType.F32)).isTrue();
        assertThat(sut.allEqual(new short[]{1, 1, 1}, PType.F16)).isTrue();
        assertThat(sut.allEqual(new short[]{1, 1, 2}, PType.F16)).isFalse();
    }

    @Test
    void allEqual_booleans() {
        // Given
        boolean[] longTail = new boolean[600];
        longTail[599] = true;

        // When / Then
        assertThat(sut.allEqual(new boolean[0])).isTrue();
        assertThat(sut.allEqual(new boolean[]{true, true})).isTrue();
        assertThat(sut.allEqual(new boolean[]{false, true})).isFalse();
        assertThat(sut.allEqual(longTail)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32"})
    void sum_narrowIntegers_matchWidenedSum(PType ptype) {
        // Given full-range values; the narrow widths cannot overflow a long
        Object values = randomArray(ptype, 1000, new Random(ptype.ordinal()));
        long expected = 0;
        for (long v : PrimitiveArrays.toLongs(values, ptype, EncodingId.VORTEX_PRIMITIVE)) {
            expected += v;
        }

        // When
        OptionalLong result = sut.sum(values, ptype);

        // Then
        assertThat(result).hasValue(expected);
    }

    @Test
    void sum_i64_overflowOfAPartialSumIsOverflowEvenIfTheTotalFits() {
        // Given a prefix sum past Long.MAX_VALUE that the last term brings back: Rust's checked_add
        // drops the sum, while a final-total check would not notice
        long[] values = {Long.MAX_VALUE, 1L, -1L};

        // When
        OptionalLong result = sut.sum(values, PType.I64);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void sum_i64_fitsAtTheBoundary() {
        // Given
        long[] values = {Long.MAX_VALUE, -1L, 1L};

        // When / Then
        assertThat(sut.sum(values, PType.I64)).hasValue(Long.MAX_VALUE);
        assertThat(sut.sum(new long[]{Long.MIN_VALUE, -1L}, PType.I64)).isEmpty();
    }

    @Test
    void sum_u64_overflowPastTwoToThe64IsEmpty_andLargeUnsignedSumsFit() {
        // Given
        long[] fits = {Long.MIN_VALUE, Long.MAX_VALUE};   // 2^63 + (2^63 - 1) = 2^64 - 1
        long[] overflows = {-1L, 1L};                      // 2^64 - 1 + 1

        // When / Then
        assertThat(sut.sum(fits, PType.U64)).hasValue(-1L);
        assertThat(sut.sum(overflows, PType.U64)).isEmpty();
    }

    @Test
    void sum_emptyArray_isZero() {
        // Given / When / Then
        assertThat(sut.sum(new int[0], PType.I32)).hasValue(0L);
        assertThat(sut.sumFloating(new double[0], PType.F64)).isZero();
    }

    @Test
    void sum_floatingPType_throws() {
        // Given / When / Then
        assertThatThrownBy(() -> sut.sum(new float[]{1f}, PType.F32)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sut.sumFloating(new int[]{1}, PType.I32)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sumFloating_addsStrictlyLeftToRight() {
        // Given terms where reassociation changes the result: (1e16 + 1) + 1 = 1e16, but 1e16 + (1 + 1) is larger
        double[] values = {1e16, 1.0, 1.0};

        // When
        double result = sut.sumFloating(values, PType.F64);

        // Then the sequential result, not the reassociated one
        assertThat(result).isEqualTo(1e16);
        assertThat(sut.sumFloating(new float[]{0.5f, 0.25f}, PType.F32)).isEqualTo(0.75);
        assertThat(sut.sumFloating(new short[]{Float.floatToFloat16(1.5f), Float.floatToFloat16(2.0f)}, PType.F16))
                .isEqualTo(3.5);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void widenArrayInto_matchesSegmentWiden(PType ptype) {
        // Given the same full-range values as a heap array and as the equivalent segment
        Object values = randomArray(ptype, 100, new Random(ptype.ordinal()));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = PrimitiveArrays.toSegment(values, ptype, arena);
            long[] expected = new long[60];
            sut.widenInto(segment, 17, 60, ptype, expected);

            // When widened from the same element, into an oversized out
            long[] result = new long[61];
            sut.widenArrayInto(values, 17, 60, ptype, result);

            // Then the heap path agrees with the segment path, and leaves the tail untouched
            assertThat(result).startsWith(expected);
            assertThat(result[60]).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void widenArrayInto_floatingPType_throws(PType ptype) {
        // Given
        long[] out = new long[1];

        // When / Then
        assertThatThrownBy(() -> sut.widenArrayInto(new double[]{1.0}, 0, 1, ptype, out))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void minMax_matchesWidenedSignedReference(PType ptype) {
        // Given seeded-random arrays of full-range values, so a wrong width or sign/zero extension
        // changes the answer; lengths straddle the vector lane counts
        Random random = new Random(ptype.ordinal());
        for (int length : new int[]{1, 2, 7, 33, 1000}) {
            Object values = randomArray(ptype, length, random);
            long[] widened = PrimitiveArrays.toLongs(values, ptype, EncodingId.VORTEX_PRIMITIVE);
            long expectedMin = Long.MAX_VALUE;
            long expectedMax = Long.MIN_VALUE;
            for (long v : widened) {
                expectedMin = Math.min(expectedMin, v);
                expectedMax = Math.max(expectedMax, v);
            }

            // When
            long[] result = sut.minMax(values, ptype);

            // Then the signed min and max of the widened values
            assertThat(result).containsExactly(expectedMin, expectedMax);
        }
    }

    @Test
    void minMax_u32AboveSignedRange_isZeroExtended() {
        // Given U32 values straddling 2^31, where sign-bit handling would swap min and max
        int[] values = {0x8000_0000, 5, 0xFFFF_FFFF};

        // When
        long[] result = sut.minMax(values, PType.U32);

        // Then
        assertThat(result).containsExactly(5L, 0xFFFF_FFFFL);
    }

    @Test
    void minMax_u64AboveSignedRange_isOrderedAsSigned() {
        // Given a U64 value with the top bit set, which sits at a negative signed position
        long[] values = {1L, -1L};

        // When
        long[] result = sut.minMax(values, PType.U64);

        // Then the contract is signed order over the widened values (ArrayStats' dense span relies on it)
        assertThat(result).containsExactly(-1L, 1L);
    }

    @Test
    void minMax_emptyArray_throws() {
        // Given
        int[] values = {};

        // When / Then
        assertThatThrownBy(() -> sut.minMax(values, PType.I32)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void minMax_floatingPType_throws(PType ptype) {
        // Given
        Object values = ptype == PType.F64 ? new double[]{1.0} : ptype == PType.F32 ? new float[]{1f} : new short[]{1};

        // When / Then
        assertThatThrownBy(() -> sut.minMax(values, ptype)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "F16", "I32", "U32", "F32"})
    void narrowInto_matchesPTypeIoSet(PType ptype) {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment expected = arena.allocate(VALUES.length * ptype.byteSize());
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(expected, i * ptype.byteSize(), ptype, VALUES[i]);
            }
            MemorySegment result = arena.allocate(VALUES.length * ptype.byteSize());

            // When
            sut.narrowInto(VALUES, ptype, result);

            // Then
            assertThat(result.mismatch(expected)).isEqualTo(-1L);
        }
    }

    @Test
    void widenInto_countZero_writesNothing() {
        // Given
        long[] result = {7L};

        // When
        sut.widenInto(MemorySegment.NULL, 0, 0, PType.I32, result);

        // Then
        assertThat(result).containsExactly(7L);
    }

    @ParameterizedTest
    @CsvSource({"U8, 0", "U8, 1", "U8, 7", "U8, 1000", "U16, 3", "U16, 1001", "U32, 5", "U32, 1003"})
    void maxUnsigned_findsMaxAtEveryPosition(PType ptype, int count) {
        // Given small random values with the type's top value planted at each position in turn:
        // that covers each of the four accumulators and the tail, and the top value has the sign
        // bit set, so a signed compare (or sign extension) anywhere would lose it
        long top = FastLanes.lowMask(ptype.byteSize() * 8);
        Random random = new Random(count);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(Math.max(count, 1) * (long) ptype.byteSize());
            for (int i = 0; i < count; i++) {
                PTypeIO.set(src, (long) i * ptype.byteSize(), ptype, random.nextInt(100));
            }
            for (int planted = 0; planted < count; planted++) {
                long saved = PrimitiveArrays.readLong(src, (long) planted * ptype.byteSize(), ptype,
                        EncodingId.VORTEX_PRIMITIVE);
                PTypeIO.set(src, (long) planted * ptype.byteSize(), ptype, top);

                // When
                long result = sut.maxUnsigned(src, count, ptype);

                // Then
                assertThat(result).as("top planted at %d", planted).isEqualTo(top);
                PTypeIO.set(src, (long) planted * ptype.byteSize(), ptype, saved);
            }
            assertThat(sut.maxUnsigned(src, count, ptype)).isLessThan(100);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "U64", "F32"})
    void maxUnsigned_nonCodePType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.maxUnsigned(MemorySegment.NULL, 0, ptype))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void widenInto_floatingPType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.widenInto(MemorySegment.NULL, 0, 0, ptype, new long[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I64", "U64", "F64"})
    void narrowInto_eightByteType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.narrowInto(new long[0], ptype, MemorySegment.NULL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 32, 64})
    void undeltaChunk_matchesLaneByLaneReference(int typeBits) {
        // Given random deltas wider than the element (as sign-extended signed values are), so a
        // missing final mask shows up; the reference is the original lane-outer prefix sum
        Random random = new Random(typeBits);
        int lanes = FastLanes.CHUNK / typeBits;
        long mask = FastLanes.lowMask(typeBits);
        long[] deltas = random.longs(FastLanes.CHUNK).toArray();
        long[] bases = random.longs(lanes).toArray();
        long[] expected = new long[FastLanes.CHUNK];
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = FastLanes.iterateIndex(row, lane);
                prev = ((deltas[idx] & mask) + prev) & mask;
                expected[idx] = prev;
            }
        }

        // When
        long[] result = new long[FastLanes.CHUNK];
        sut.undeltaChunk(deltas, bases, lanes, typeBits, mask, result);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 32, 64})
    void deltaChunk_matchesLaneByLaneReference_andUndeltaInverts(int typeBits) {
        // Given
        Random random = new Random(typeBits);
        int lanes = FastLanes.CHUNK / typeBits;
        long mask = FastLanes.lowMask(typeBits);
        long[] values = random.longs(FastLanes.CHUNK).toArray();
        long[] bases = random.longs(lanes).toArray();
        long[] expected = new long[FastLanes.CHUNK];
        for (int lane = 0; lane < lanes; lane++) {
            long prev = bases[lane] & mask;
            for (int row = 0; row < typeBits; row++) {
                int idx = FastLanes.iterateIndex(row, lane);
                long next = values[idx] & mask;
                expected[idx] = (next - prev) & mask;
                prev = next;
            }
        }

        // When
        long[] result = new long[FastLanes.CHUNK];
        sut.deltaChunk(values, bases, lanes, typeBits, mask, result);

        // Then it matches the reference, and undelta restores the masked input
        assertThat(result).isEqualTo(expected);
        long[] restored = new long[FastLanes.CHUNK];
        sut.undeltaChunk(result, bases, lanes, typeBits, mask, restored);
        for (int i = 0; i < FastLanes.CHUNK; i++) {
            assertThat(restored[i]).isEqualTo(values[i] & mask);
        }
    }

    @ParameterizedTest
    @CsvSource({"8,1", "8,3", "8,8", "16,5", "16,16", "32,7", "32,13", "32,32", "64,1", "64,33", "64,64"})
    void packBlock_matchesBitByBitReference(int typeBits, int bitWidth) {
        // Given random values wider than bitWidth (patch candidates), and an offset into the array
        Random random = new Random(typeBits * 100L + bitWidth);
        int lanes = FastLanes.CHUNK / typeBits;
        long[] values = random.longs(FastLanes.CHUNK + 5).toArray();
        long widthMask = bitWidth >= 64 ? -1L : (1L << bitWidth) - 1L;

        // The reference places value (row, lane) at bit row*bitWidth of the lane's bitstream, one
        // bit at a time, so it shares no shift/carry logic with the kernel
        long[] expected = new long[bitWidth * lanes];
        for (int lane = 0; lane < lanes; lane++) {
            for (int row = 0; row < typeBits; row++) {
                long value = values[5 + FastLanes.iterateIndex(row, lane)] & widthMask;
                for (int bit = 0; bit < bitWidth; bit++) {
                    int position = row * bitWidth + bit;
                    int word = position / typeBits;
                    int inWord = position % typeBits;
                    expected[word * lanes + lane] |= ((value >>> bit) & 1L) << inWord;
                }
            }
        }

        // When
        long[] result = new long[bitWidth * lanes];
        sut.packBlock(values, 5, bitWidth, typeBits, result);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private static Object randomArray(PType ptype, int length, Random random) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[length];
                random.nextBytes(a);
                yield a;
            }
            case I16, U16 -> {
                short[] a = new short[length];
                for (int i = 0; i < length; i++) {
                    a[i] = (short) random.nextInt();
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[length];
                for (int i = 0; i < length; i++) {
                    a[i] = random.nextInt();
                }
                yield a;
            }
            case I64, U64 -> {
                long[] a = new long[length];
                for (int i = 0; i < length; i++) {
                    a[i] = random.nextLong();
                }
                yield a;
            }
            default -> throw new IllegalArgumentException(ptype.toString());
        };
    }

    private static Object constantArray(PType ptype, int length) {
        Object a = randomArray(ptype, 1, new Random(7));
        Object result = Array.newInstance(a.getClass().getComponentType(), length);
        for (int i = 0; i < length; i++) {
            Array.set(result, i, Array.get(a, 0));
        }
        return result;
    }

    private static void bump(Object array, int index) {
        switch (array) {
            case byte[] a -> a[index]++;
            case short[] a -> a[index]++;
            case int[] a -> a[index]++;
            case long[] a -> a[index]++;
            default -> throw new IllegalArgumentException(array.getClass().toString());
        }
    }
}
