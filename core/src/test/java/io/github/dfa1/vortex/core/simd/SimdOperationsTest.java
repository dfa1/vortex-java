package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The behavior every [SimdOperations] implementation must have; a subclass supplies the one under test.
abstract class SimdOperationsTest {

    // Full-range values: a wrong width, or sign vs zero extension, changes the result
    private static final long[] VALUES = {0L, -1L, 1L, 0x80L, 0xFFL, 0x8000L, 0xFFFFL, 0x80000000L,
            0xFFFFFFFFL, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEFL};

    protected SimdOperations sut;

    /// Creates the implementation under test, skipping the test if it cannot run in this JVM.
    ///
    /// @return the implementation under test
    protected abstract SimdOperations createSut();

    @BeforeEach
    void setUpSut() {
        sut = createSut();
    }

    @Test
    void sumFloating_skipsNaN() {
        // Given NaN in the middle and at the tail (Rust's skip_nans), for every float carrier
        double[] doubles = {1.0, Double.NaN, 2.0, Double.NaN};
        float[] floats = {1f, Float.NaN, 2f, Float.NaN};
        short[] halves = {Float.floatToFloat16(1f), Float.floatToFloat16(Float.NaN), Float.floatToFloat16(2f)};

        // When / Then
        assertThat(sut.sumFloating(doubles, PType.F64)).isEqualTo(3.0);
        assertThat(sut.sumFloating(floats, PType.F32)).isEqualTo(3.0);
        assertThat(sut.sumFloating(halves, PType.F16)).isEqualTo(3.0);
        assertThat(sut.sumFloating(new double[]{Double.NaN}, PType.F64)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void widenInto_roundTripsNarrowedValues(PType ptype) {
        // Given values narrowed to ptype's width with the PTypeIO reference writer
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate((long) VALUES.length * ptype.byteSize());
            long[] expected = new long[VALUES.length];
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(src, (long) i * ptype.byteSize(), ptype, VALUES[i]);
                expected[i] = PrimitiveArrays.readLong(src, (long) i * ptype.byteSize(), ptype, EncodingId.VORTEX_PRIMITIVE);
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
    void sum_i64_prefixOverflowPastTheVectorWidthIsStillOverflow() {
        // Given the [MAX, 1, -1] case buried in an array long enough to take the vector loop, where no
        // single lane overflows
        long[] values = new long[64];
        values[0] = Long.MAX_VALUE;
        values[1] = 1L;
        values[2] = -1L;

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
    void runs_countsChangesBetweenNeighbors(PType ptype) {
        // Given a constant array and then one with a change at known positions
        Object constant = constantArray(ptype, 500);
        Object twoChanges = constantArray(ptype, 500);
        bump(twoChanges, 100);
        bump(twoChanges, 101);

        // When / Then a constant array is one run; bumping 100 and 101 to the same new value
        // adds a change into it and one out of it
        assertThat(sut.runs(constant, ptype)).isEqualTo(1);
        assertThat(sut.runs(twoChanges, ptype)).isEqualTo(3);
        assertThat(sut.runs(constantArray(ptype, 1), ptype)).isEqualTo(1);
        assertThat(sut.runs(constantArray(ptype, 0), ptype)).isZero();
    }

    @Test
    void runs_floatsCompareByValue() {
        // Given NaN, which never equals itself, and a signed zero pair, which compares equal
        float[] nans = {Float.NaN, Float.NaN, Float.NaN};
        double[] zeros = {0.0, -0.0, 0.0};

        // When / Then (unlike allEqual, which compares raw bits)
        assertThat(sut.runs(nans, PType.F32)).isEqualTo(3);
        assertThat(sut.runs(zeros, PType.F64)).isEqualTo(1);
        assertThat(sut.runs(new short[]{1, 1, 2, 2, 3}, PType.F16)).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32"})
    void narrowArrayInto_keepsTheLowBytes_andWidenRestoresThem(PType ptype) {
        // Given full-range wide values, so the dropped high bytes are not all zero
        Object carrier = randomArray(ptype, VALUES.length, new Random(1));
        long[] result = VALUES.clone();

        // When narrowed into an array and widened back
        sut.narrowArrayInto(VALUES, ptype, carrier);
        sut.widenArrayInto(carrier, 0, VALUES.length, ptype, result);

        // Then each value equals the original truncated to the width and re-extended
        for (int i = 0; i < VALUES.length; i++) {
            long truncated = ptype.isSigned()
                    ? VALUES[i] << (64 - 8 * ptype.byteSize()) >> (64 - 8 * ptype.byteSize())
                    : VALUES[i] & (-1L >>> (64 - 8 * ptype.byteSize()));
            assertThat(result[i]).as("%s[%d]", ptype, i).isEqualTo(truncated);
        }
    }

    @Test
    void narrowArrayInto_eightByteTypes_copy_andFloatCarriersAreRebuiltFromRawBits() {
        // Given the full-range wide values
        long[] copied = new long[VALUES.length];
        short[] halves = new short[VALUES.length];
        float[] floats = new float[VALUES.length];
        double[] doubles = new double[VALUES.length];

        // When
        sut.narrowArrayInto(VALUES, PType.U64, copied);
        sut.narrowArrayInto(VALUES, PType.F16, halves);
        sut.narrowArrayInto(VALUES, PType.F32, floats);
        sut.narrowArrayInto(VALUES, PType.F64, doubles);

        // Then the 8-byte types are unchanged and each float carrier holds the low bits of the value
        assertThat(copied).containsExactly(VALUES);
        for (int i = 0; i < VALUES.length; i++) {
            assertThat(halves[i]).isEqualTo((short) VALUES[i]);
            assertThat(Float.floatToRawIntBits(floats[i])).isEqualTo((int) VALUES[i]);
            assertThat(Double.doubleToRawLongBits(doubles[i])).isEqualTo(VALUES[i]);
        }
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

    @Test
    void widenArrayInto_floats_contributeTheirRawBitsZeroExtended() {
        // Given a negative float, whose sign bit must not be sign-extended
        float[] floats = {-1.5f, 2.5f};
        double[] doubles = {-1.5, 2.5};
        short[] halves = {(short) 0xBE00, 0x4100};
        long[] out = new long[2];

        // When / Then
        sut.widenArrayInto(floats, 0, 2, PType.F32, out);
        assertThat(out).containsExactly(Float.floatToRawIntBits(-1.5f) & 0xFFFF_FFFFL, Float.floatToRawIntBits(2.5f) & 0xFFFF_FFFFL);
        sut.widenArrayInto(doubles, 0, 2, PType.F64, out);
        assertThat(out).containsExactly(Double.doubleToRawLongBits(-1.5), Double.doubleToRawLongBits(2.5));
        sut.widenArrayInto(halves, 0, 2, PType.F16, out);
        assertThat(out).containsExactly(0xBE00L, 0x4100L);
    }


    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void minMax_matchesWidenedReference(PType ptype) {
        // Given seeded-random arrays of full-range values, so a wrong width or sign/zero extension
        // changes the answer; lengths straddle the vector lane counts
        Random random = new Random(ptype.ordinal());
        for (int length : new int[]{1, 2, 7, 33, 1000}) {
            Object values = randomArray(ptype, length, random);
            long[] widened = PrimitiveArrays.toLongs(values, ptype, EncodingId.VORTEX_PRIMITIVE);
            long expectedMin = widened[0];
            long expectedMax = widened[0];
            for (long v : widened) {
                // U64 is ordered unsigned; the narrower widths are zero-extended, so signed order is theirs
                boolean lower = ptype == PType.U64 ? Long.compareUnsigned(v, expectedMin) < 0 : v < expectedMin;
                boolean higher = ptype == PType.U64 ? Long.compareUnsigned(v, expectedMax) > 0 : v > expectedMax;
                expectedMin = lower ? v : expectedMin;
                expectedMax = higher ? v : expectedMax;
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
    void minMax_u64AboveSignedRange_isOrderedUnsigned() {
        // Given a U64 value with the top bit set, which sits at a negative signed position
        long[] values = {1L, -1L, 5L};

        // When
        long[] result = sut.minMax(values, PType.U64);

        // Then the natural unsigned order: 2^64 - 1 is the maximum
        assertThat(result).containsExactly(1L, -1L);
    }


    @Test
    void minMax_emptyArray_throws() {
        // Given
        int[] values = {};

        // When / Then
        assertThatThrownBy(() -> sut.minMax(values, PType.I32)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minMax_floats_skipNaN_andOrderNegativeZeroFirst() {
        // Given NaN around the extremes, and zeros of both signs
        float[] floats = {Float.NaN, 3f, -0.0f, 0.0f, -2f, Float.NaN, 7f};
        double[] zeros = {0.0, -0.0, 5.0};
        double[] negativeZeros = {-0.0, 0.0, -5.0};
        short[] halves = {(short) 0x7E00, 0x4200, (short) 0xC000};

        // When / Then NaN never wins, and -0.0 sorts before 0.0 (Rust's total order)
        assertThat(sut.minMax(floats, PType.F32)).containsExactly(
                Float.floatToRawIntBits(-2f) & 0xFFFF_FFFFL, Float.floatToRawIntBits(7f) & 0xFFFF_FFFFL);
        assertThat(sut.minMax(zeros, PType.F64)).containsExactly(
                Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(5.0));
        assertThat(sut.minMax(negativeZeros, PType.F64)).containsExactly(
                Double.doubleToRawLongBits(-5.0), Double.doubleToRawLongBits(0.0));
        assertThat(sut.minMax(halves, PType.F16)).containsExactly(0xC000L, 0x4200L);
    }

    @Test
    void minMax_floatZerosOnly_pickNegativeZeroAsMinAndPositiveZeroAsMax() {
        // Given only zeros of both signs
        double[] zeros = {0.0, -0.0, 0.0};

        // When
        long[] result = sut.minMax(zeros, PType.F64);

        // Then
        assertThat(result).containsExactly(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(0.0));
    }

    @Test
    void minMax_halfAllPositiveInfinity_returnsInfinityNotZero() {
        // Given F16 +Infinity (0x7C00), which a bit-tracking loop seeded with zeros would lose
        short[] halves = {0x7C00, 0x7C00};

        // When
        long[] result = sut.minMax(halves, PType.F16);

        // Then
        assertThat(result).containsExactly(0x7C00L, 0x7C00L);
    }

    @Test
    void minMax_floatsWithoutAnOrderedElement_haveNoMinMax() {
        // Given only NaN, and no elements at all
        float[] nans = {Float.NaN, Float.NaN};

        // When / Then
        assertThat(sut.minMax(nans, PType.F32)).isEmpty();
        assertThat(sut.minMax(new double[0], PType.F64)).isEmpty();
        assertThat(sut.minMax(new short[0], PType.F16)).isEmpty();
    }


    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "F16", "I32", "U32", "F32"})
    void narrowInto_matchesPTypeIoSet(PType ptype) {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment expected = arena.allocate((long) VALUES.length * ptype.byteSize());
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(expected, (long) i * ptype.byteSize(), ptype, VALUES[i]);
            }
            MemorySegment result = arena.allocate((long) VALUES.length * ptype.byteSize());

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
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "I64", "F32"})
    void maxUnsigned_nonCodePType_throws(PType ptype) {
        // When / Then
        assertThatThrownBy(() -> sut.maxUnsigned(MemorySegment.NULL, 0, ptype))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void widenInto_floats_readLikeTheUnsignedTypeOfTheSameWidth(PType ptype) {
        // Given a segment of full-range bits
        PType unsigned = switch (ptype) {
            case F16 -> PType.U16;
            case F32 -> PType.U32;
            default -> PType.U64;
        };
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate((long) VALUES.length * ptype.byteSize());
            for (int i = 0; i < VALUES.length; i++) {
                PTypeIO.set(src, (long) i * ptype.byteSize(), unsigned, VALUES[i]);
            }
            long[] expected = new long[VALUES.length];
            long[] result = new long[VALUES.length];

            // When
            sut.widenInto(src, 0, VALUES.length, unsigned, expected);
            sut.widenInto(src, 0, VALUES.length, ptype, result);

            // Then
            assertThat(result).containsExactly(expected);
        }
    }


    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I64", "U64", "F64"})
    void narrowInto_eightByteTypes_writeTheValuesLittleEndian(PType ptype) {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(VALUES.length * 8L);

            // When
            sut.narrowInto(VALUES, ptype, dst);

            // Then
            for (int i = 0; i < VALUES.length; i++) {
                assertThat(dst.getAtIndex(VortexFormat.LE_LONG, i)).isEqualTo(VALUES[i]);
            }
        }
    }

    @Test
    void maxUnsigned_u64_ordersTheHighBitAboveEverything() {
        // Given a code with the top bit set among small ones
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(4 * 8L);
            long[] codes = {5L, -2L, 7L, 1L};
            for (int i = 0; i < codes.length; i++) {
                src.setAtIndex(VortexFormat.LE_LONG, i, codes[i]);
            }

            // When
            long result = sut.maxUnsigned(src, 4, PType.U64);

            // Then
            assertThat(result).isEqualTo(-2L);
        }
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

    private static final int[] LENGTHS = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 255, 256, 257, 1000};

    // The default implementation is the Rust-parity oracle for the differential tests below
    private final SimdOperations reference = new AutoVectorizedSimdOperations();

    @ParameterizedTest
    @EnumSource(PType.class)
    void runs_matchesReference(PType ptype) {
        // Given arrays with long runs, short runs and no runs at every length, and NaN / signed zero floats
        for (int length : LENGTHS) {
            for (int runLength : new int[]{1, 2, 5, 64}) {
                Object values = arrayWithRuns(ptype, length, runLength, new Random(length * 31L + runLength));

                // When
                long result = sut.runs(values, ptype);

                // Then
                assertThat(result).as("%s length=%d runLength=%d", ptype, length, runLength)
                        .isEqualTo(reference.runs(values, ptype));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void allEqual_matchesReference(PType ptype) {
        // Given a constant array at every length, and the same array with one element changed at the
        // first, a middle, the last and the first-tail position
        for (int length : LENGTHS) {
            Object constant = arrayWithRuns(ptype, length, Integer.MAX_VALUE, new Random(length));
            assertThat(sut.allEqual(constant, ptype)).as("%s constant length=%d", ptype, length)
                    .isEqualTo(reference.allEqual(constant, ptype));
            for (int position : new int[]{0, length / 2, length - 1, length - length % 16 - 1}) {
                if (position < 0 || position >= length) {
                    continue;
                }
                Object changed = arrayWithRuns(ptype, length, Integer.MAX_VALUE, new Random(length));
                flip(changed, position);

                // When / Then
                assertThat(sut.allEqual(changed, ptype)).as("%s length=%d differs at %d", ptype, length, position)
                        .isEqualTo(reference.allEqual(changed, ptype));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void minMax_matchesReference(PType ptype) {
        // Given random full-range data at every length: values near the sign boundary, where the
        // unsigned flip would swap min and max if wrong
        for (int length : LENGTHS) {
            if (length == 0 && !ptype.isFloating()) {
                continue;
            }
            Object values = arrayWithRuns(ptype, length, 1, new Random(length * 17L));

            // When
            long[] result = sut.minMax(values, ptype);

            // Then
            assertThat(result).as("%s length=%d", ptype, length).containsExactly(reference.minMax(values, ptype));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void minMax_extremes_matchReference(PType ptype) {
        // Given arrays holding only the extremes of the carrier, in a vector-length run and a tail
        for (int length : new int[]{1, 5, 17, 64, 70}) {
            Object values = arrayWithRuns(ptype, length, 1, new Random(1));
            extremes(values);

            // When / Then
            assertThat(sut.minMax(values, ptype)).as("%s length=%d", ptype, length)
                    .containsExactly(reference.minMax(values, ptype));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "U64"})
    void maxUnsigned_matchesReference(PType ptype) {
        // Given random codes, with the maximum planted at every position class: first, last, a lane boundary
        try (Arena arena = Arena.ofConfined()) {
            for (int length : LENGTHS) {
                MemorySegment segment = arena.allocate(Math.max(1L, (long) length * ptype.byteSize()));
                Random random = new Random(length);
                for (long b = 0; b < segment.byteSize(); b++) {
                    segment.set(ValueLayout.JAVA_BYTE, b, (byte) random.nextInt(256));
                }

                // When / Then
                assertThat(sut.maxUnsigned(segment, length, ptype)).as("%s length=%d", ptype, length)
                        .isEqualTo(reference.maxUnsigned(segment, length, ptype));
            }
        }
    }

    @Test
    void sum_i64_nearTheFastPathBound_matchesReference() {
        // Given, per length, values hugging the +-2^k edge where the vector path hands over to the checked loop
        // (k = 63 - ceil(log2 n)): an off-by-one in k, or floor instead of ceil, flips the answer at some length
        Random random = new Random(535);
        for (int length : LENGTHS) {
            int log2n = length <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(length - 1L);
            long edge = 1L << (63 - log2n);
            long[][] pool = {{edge - 1, -edge}, {edge, -edge - 1}, {Long.MAX_VALUE, Long.MIN_VALUE}};
            for (int round = 0; round < 20; round++) {
                long[] values = new long[length];
                for (int i = 0; i < length; i++) {
                    values[i] = random.nextInt(4) == 0 ? random.nextLong() >> random.nextInt(64)
                            : pool[random.nextInt(2)][random.nextInt(2)];
                }
                if (length > 0 && random.nextBoolean()) {
                    values[random.nextInt(length)] = pool[2][random.nextInt(2)];
                }

                // When / Then
                assertThat(sut.sum(values, PType.I64)).as("length=%d round=%d", length, round)
                        .isEqualTo(reference.sum(values, PType.I64));
            }
        }
    }

    @Test
    void sum_i64_exactEdgeOfTheBound() {
        // Given n * 2^k == 2^63 exactly (8 * -2^60), and three values whose sum passes MAX though n=3 rounds to 4
        long[] fits = new long[8];
        java.util.Arrays.fill(fits, -(1L << 60));
        long[] overflows = {(1L << 62) - 1, (1L << 62) - 1, (1L << 62) - 1};

        // When / Then
        assertThat(sut.sum(fits, PType.I64)).hasValue(Long.MIN_VALUE);
        assertThat(sut.sum(overflows, PType.I64)).isEqualTo(reference.sum(overflows, PType.I64)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void sum_matchesReference(PType ptype) {
        // Given random data at every length; the 64-bit types hold values below 2^40 so no partial sum
        // overflows: the vector path of I64, and U64 without carries
        for (int length : LENGTHS) {
            Object values = arrayWithRuns(ptype, length, 1, new Random(length * 13L));
            if (values instanceof long[] longs) {
                for (int i = 0; i < longs.length; i++) {
                    longs[i] >>= 24;
                    longs[i] = ptype == PType.U64 ? longs[i] & 0xFF_FFFF_FFFFL : longs[i];
                }
            }

            // When / Then
            assertThat(sut.sum(values, ptype)).as("%s length=%d", ptype, length).isEqualTo(reference.sum(values, ptype));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32"})
    void sum_pastTheFlushWindow_doesNotOverflowALane(PType ptype) {
        // Given more elements than one int-lane flush window holds, every one at the carrier's extreme
        // (255 / 65535 / -32768): a missing flush would wrap an int lane here
        int length = 600_000;
        Object values = arrayWithRuns(ptype, length, Integer.MAX_VALUE, new Random(1));
        extremes(values);
        fillConstant(values);

        // When
        OptionalLong result = sut.sum(values, ptype);

        // Then
        assertThat(result).isEqualTo(reference.sum(values, ptype));
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void widenArrayInto_matchesReference(PType ptype) {
        // Given full-range data widened from several start offsets, so the vector body starts unaligned
        for (int length : LENGTHS) {
            Object values = arrayWithRuns(ptype, length + 5, 1, new Random(length * 7L));
            for (int from : new int[]{0, 1, 5}) {
                long[] expected = new long[length + 1];
                long[] result = new long[length + 1];

                // When
                reference.widenArrayInto(values, from, length, ptype, expected);
                sut.widenArrayInto(values, from, length, ptype, result);

                // Then, including the untouched slot past count
                assertThat(result).as("%s length=%d from=%d", ptype, length, from).containsExactly(expected);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void widenInto_matchesReference(PType ptype) {
        // Given full-range data in a segment, widened from several start elements
        try (Arena arena = Arena.ofConfined()) {
            for (int length : LENGTHS) {
                Object values = arrayWithRuns(ptype, length + 5, 1, new Random(length * 11L));
                MemorySegment segment = PrimitiveArrays.toSegment(values, ptype, arena);
                for (long from : new long[]{0, 1, 5}) {
                    long[] expected = new long[length + 1];
                    long[] result = new long[length + 1];

                    // When
                    reference.widenInto(segment, from, length, ptype, expected);
                    sut.widenInto(segment, from, length, ptype, result);

                    // Then
                    assertThat(result).as("%s length=%d from=%d", ptype, length, from).containsExactly(expected);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void narrowArrayInto_matchesReference(PType ptype) {
        // Given full-range wide values at every length
        for (int length : LENGTHS) {
            long[] values = new Random(length * 3L).longs(length).toArray();
            Object expected = narrowCarrier(ptype, length);
            Object result = narrowCarrier(ptype, length);

            // When
            reference.narrowArrayInto(values, ptype, expected);
            sut.narrowArrayInto(values, ptype, result);

            // Then
            assertThat(result).as("%s length=%d", ptype, length).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(PType.class)
    void narrowInto_matchesReference(PType ptype) {
        // Given full-range wide values written into segments of exactly the narrowed size
        try (Arena arena = Arena.ofConfined()) {
            for (int length : LENGTHS) {
                long[] values = new Random(length * 5L).longs(length).toArray();
                MemorySegment expected = arena.allocate(Math.max(1L, (long) length * ptype.byteSize()));
                MemorySegment result = arena.allocate(Math.max(1L, (long) length * ptype.byteSize()));

                // When
                reference.narrowInto(values, ptype, expected);
                sut.narrowInto(values, ptype, result);

                // Then
                assertThat(result.mismatch(expected)).as("%s length=%d", ptype, length).isEqualTo(-1L);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void sumFloating_agreesWithTheReferenceToRoundingError(PType ptype) {
        // Given values small enough that the sum is well inside the double range
        for (int length : LENGTHS) {
            Object values = arrayWithRuns(ptype, length, 1, new Random(length * 19L));
            if (values instanceof float[] f) {
                for (int i = 0; i < f.length; i++) {
                    f[i] = Float.isFinite(f[i]) ? (float) (f[i] % 1e6) : 1f;
                }
            } else if (values instanceof double[] d) {
                for (int i = 0; i < d.length; i++) {
                    d[i] = Double.isFinite(d[i]) ? d[i] % 1e12 : 1d;
                }
            } else if (values instanceof short[] h) {
                for (int i = 0; i < h.length; i++) {
                    h[i] = Float.isFinite(Float.float16ToFloat(h[i])) ? h[i] : Float.floatToFloat16(1f);
                }
            }
            double expected = reference.sumFloating(values, ptype);

            // When
            double result = sut.sumFloating(values, ptype);

            // Then bit-identical: Rust adds sequentially, so any reordering would show in the last bits
            assertThat(result).as("%s length=%d", ptype, length).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16"})
    void sum_narrowIntegersAtTheirMostExtremeValues_neverOverflowAnAccumulator(PType ptype) {
        // Given long arrays of the value that gains the most per vector in a 16-bit (bytes) or 32-bit
        // (shorts) accumulator: the minimum for signed types, the maximum for unsigned ones
        int length = 300_000;
        Object values = switch (ptype) {
            case I8 -> filled(new byte[length], Byte.MIN_VALUE);
            case U8 -> filled(new byte[length], (byte) 0xFF);
            case I16 -> filled(new short[length], Short.MIN_VALUE);
            default -> filled(new short[length], (short) 0xFFFF);
        };

        // When
        OptionalLong result = sut.sum(values, ptype);

        // Then
        assertThat(result).isEqualTo(reference.sum(values, ptype));
    }

    @Test
    void sumFloating_exactlyRepresentableValues_areEqualWhateverTheOrder() {
        // Given small integers, whose sum is exact in any order
        double[] doubles = new Random(3).doubles(1000, 0, 100).map(Math::floor).toArray();
        float[] floats = new float[1000];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = (float) doubles[i];
        }

        // When / Then
        assertThat(sut.sumFloating(doubles, PType.F64)).isEqualTo(reference.sumFloating(doubles, PType.F64));
        assertThat(sut.sumFloating(floats, PType.F32)).isEqualTo(reference.sumFloating(floats, PType.F32));
    }

    @Test
    void minMax_floatsWithNaNAndZerosOfBothSigns_matchReference() {
        // Given every arrangement of NaN, +0.0, -0.0 and ordinary values, long enough for the vector
        // body, so the lane merge sees a zero of each sign in different lanes
        float[] pool = {Float.NaN, 0.0f, -0.0f, 1f, -1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Random random = new Random(42);
        for (int trial = 0; trial < 200; trial++) {
            int length = 1 + random.nextInt(70);
            float[] floats = new float[length];
            double[] doubles = new double[length];
            for (int i = 0; i < length; i++) {
                floats[i] = pool[random.nextInt(pool.length)];
                doubles[i] = floats[i];
            }

            // When / Then
            assertThat(sut.minMax(floats, PType.F32)).as("F32 %s", Arrays.toString(floats))
                    .containsExactly(reference.minMax(floats, PType.F32));
            assertThat(sut.minMax(doubles, PType.F64)).as("F64 %s", Arrays.toString(doubles))
                    .containsExactly(reference.minMax(doubles, PType.F64));
        }
    }

    @Test
    void allEqual_booleans_matchReference() {
        // Given
        for (int length : LENGTHS) {
            boolean[] constant = new boolean[length];
            Arrays.fill(constant, true);
            assertThat(sut.allEqual(constant)).as("constant length=%d", length).isEqualTo(reference.allEqual(constant));
            for (int position : new int[]{0, length / 2, length - 1}) {
                if (position < 0 || position >= length) {
                    continue;
                }
                boolean[] changed = constant.clone();
                changed[position] = false;

                // When / Then
                assertThat(sut.allEqual(changed)).as("length=%d differs at %d", length, position)
                        .isEqualTo(reference.allEqual(changed));
            }
        }
    }

    /// An array of `length` elements in runs of `runLength` equal values, full-range per carrier.
    private static Object arrayWithRuns(PType ptype, int length, int runLength, Random random) {
        long[] base = new long[length];
        long current = random.nextLong();
        for (int i = 0; i < length; i++) {
            if (i % runLength == 0) {
                current = random.nextLong();
            }
            base[i] = current;
        }
        return switch (ptype) {
            case I8, U8 -> {
                byte[] a = new byte[length];
                for (int i = 0; i < length; i++) {
                    a[i] = (byte) base[i];
                }
                yield a;
            }
            case I16, U16, F16 -> {
                short[] a = new short[length];
                for (int i = 0; i < length; i++) {
                    a[i] = (short) base[i];
                }
                yield a;
            }
            case I32, U32 -> {
                int[] a = new int[length];
                for (int i = 0; i < length; i++) {
                    a[i] = (int) base[i];
                }
                yield a;
            }
            case I64, U64 -> base;
            case F32 -> {
                float[] a = new float[length];
                for (int i = 0; i < length; i++) {
                    a[i] = Float.intBitsToFloat((int) base[i]);
                }
                yield a;
            }
            case F64 -> {
                double[] a = new double[length];
                for (int i = 0; i < length; i++) {
                    a[i] = Double.longBitsToDouble(base[i]);
                }
                yield a;
            }
        };
    }

    /// Changes one element so that it differs from its neighbors in the raw bits.
    private static void flip(Object array, int index) {
        switch (array) {
            case byte[] a -> a[index] ^= 0x55;
            case short[] a -> a[index] ^= 0x5555;
            case int[] a -> a[index] ^= 0x55555555;
            case long[] a -> a[index] ^= 0x5555555555555555L;
            case float[] a -> a[index] = Float.intBitsToFloat(Float.floatToRawIntBits(a[index]) ^ 1);
            case double[] a -> a[index] = Double.longBitsToDouble(Double.doubleToRawLongBits(a[index]) ^ 1L);
            default -> throw new IllegalArgumentException(array.getClass().toString());
        }
    }

    /// Overwrites every other element with the carrier's minimum and maximum bit patterns.
    private static void extremes(Object array) {
        switch (array) {
            case byte[] a -> fill(a);
            case short[] a -> fill(a);
            case int[] a -> fill(a);
            case long[] a -> fill(a);
            default -> throw new IllegalArgumentException(array.getClass().toString());
        }
    }

    private static void fill(byte[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] = i % 3 == 0 ? Byte.MIN_VALUE : i % 3 == 1 ? Byte.MAX_VALUE : (byte) -1;
        }
    }

    private static void fill(short[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] = i % 3 == 0 ? Short.MIN_VALUE : i % 3 == 1 ? Short.MAX_VALUE : (short) -1;
        }
    }

    private static void fill(int[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] = i % 3 == 0 ? Integer.MIN_VALUE : i % 3 == 1 ? Integer.MAX_VALUE : -1;
        }
    }

    private static void fill(long[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] = i % 3 == 0 ? Long.MIN_VALUE : i % 3 == 1 ? Long.MAX_VALUE : -1L;
        }
    }

    /// Sets every element to the carrier's all-ones pattern: 255 / 65535 as unsigned, -1 as signed.
    private static void fillConstant(Object array) {
        switch (array) {
            case byte[] a -> Arrays.fill(a, (byte) -1);
            case short[] a -> Arrays.fill(a, (short) -1);
            case int[] a -> Arrays.fill(a, -1);
            default -> throw new IllegalArgumentException(array.getClass().toString());
        }
    }

    private static Object narrowCarrier(PType ptype, int length) {
        return switch (ptype) {
            case I8, U8 -> new byte[length];
            case I16, U16, F16 -> new short[length];
            case I32, U32 -> new int[length];
            case I64, U64 -> new long[length];
            case F32 -> new float[length];
            case F64 -> new double[length];
        };
    }

    private static byte[] filled(byte[] array, byte value) {
        Arrays.fill(array, value);
        return array;
    }

    private static short[] filled(short[] array, short value) {
        Arrays.fill(array, value);
        return array;
    }
}
