package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.OptionalLong;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Differential test: the Vector API implementation must agree with the auto-vectorized one, which
/// the unit tests of [SimdOperationsTest] pin down, for every input. Lengths straddle the lane
/// counts of every vector width so the main loop, the tail and the empty case are all hit.
class VectorApiSimdOperationsTest {

    private static final int[] LENGTHS = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 255, 256, 257, 1000};

    private final SimdOperations reference = new AutoVectorizedSimdOperations();
    private SimdOperations sut;

    @BeforeEach
    void setUp() {
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "run without the module");
        assumeTrue(VectorApiSimdOperations.isUsable(), "CPU vectors narrower than 128 bits");
        sut = new VectorApiSimdOperations();
    }

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

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "U64"})
    void maxUnsigned_highBitCodes_areUnsigned(PType ptype) {
        // Given a code with the top bit set, which a signed max would rank below a small code
        try (Arena arena = Arena.ofConfined()) {
            int length = 70;
            MemorySegment segment = arena.allocate((long) length * ptype.byteSize());
            PTypeIO.set(segment, 69L * ptype.byteSize(), ptype, -1L);
            PTypeIO.set(segment, 3L * ptype.byteSize(), ptype, 5L);

            // When
            long result = sut.maxUnsigned(segment, length, ptype);

            // Then
            assertThat(result).isEqualTo(reference.maxUnsigned(segment, length, ptype));
            assertThat(Long.compareUnsigned(result, 5L)).isPositive();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void sum_matchesReference(PType ptype) {
        // Given random data at every length; the 64-bit types hold values below 2^40 so no partial sum
        // overflows, which is the case where lane-wise and sequential overflow checks must agree
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
    @ValueSource(ints = {8, 16, 32, 64})
    void undeltaAndDeltaChunk_matchReference(int typeBits) {
        // Given a random chunk and bases
        int lanes = FastLanes.CHUNK / typeBits;
        long mask = FastLanes.lowMask(typeBits);
        Random random = new Random(typeBits);
        long[] chunk = new long[FastLanes.CHUNK];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = random.nextLong() & mask;
        }
        long[] bases = new long[lanes];
        for (int i = 0; i < lanes; i++) {
            bases[i] = random.nextLong() & mask;
        }
        long[] expectedUndelta = new long[FastLanes.CHUNK];
        long[] resultUndelta = new long[FastLanes.CHUNK];
        long[] expectedDelta = new long[FastLanes.CHUNK];
        long[] resultDelta = new long[FastLanes.CHUNK];

        // When
        reference.undeltaChunk(chunk, bases, lanes, typeBits, mask, expectedUndelta);
        sut.undeltaChunk(chunk, bases, lanes, typeBits, mask, resultUndelta);
        reference.deltaChunk(chunk, bases, lanes, typeBits, mask, expectedDelta);
        sut.deltaChunk(chunk, bases, lanes, typeBits, mask, resultDelta);

        // Then
        assertThat(resultUndelta).as("undelta %d", typeBits).containsExactly(expectedUndelta);
        assertThat(resultDelta).as("delta %d", typeBits).containsExactly(expectedDelta);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 32, 64})
    void packBlock_matchesReference_atEveryBitWidth(int typeBits) {
        // Given a random block, including bits above the width (which packBlock must mask off)
        Random random = new Random(typeBits * 101L);
        long[] values = random.longs(FastLanes.CHUNK + 7).toArray();
        for (int bitWidth = 1; bitWidth <= typeBits; bitWidth++) {
            long[] expected = new long[bitWidth * (FastLanes.CHUNK / typeBits)];
            long[] result = new long[expected.length];

            // When
            reference.packBlock(values, 7, bitWidth, typeBits, expected);
            sut.packBlock(values, 7, bitWidth, typeBits, result);

            // Then
            assertThat(result).as("typeBits=%d bitWidth=%d", typeBits, bitWidth).containsExactly(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"F16", "F32", "F64"})
    void sumFloating_agreesWithTheReferenceToRoundingError(PType ptype) {
        // Given values small enough that the sum is well inside the double range. The Vector API sums
        // lane-wise, so the last bits legitimately differ from the sequential reference.
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

            // Then within a relative 1e-9 of the sum of the magnitudes (the error scale of reordering)
            double scale = Math.max(1.0, magnitude(values, ptype));
            assertThat(result).as("%s length=%d", ptype, length).isCloseTo(expected,
                    org.assertj.core.data.Offset.offset(scale * 1e-9));
        }
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
    void sum_signed64_clearOverflowIsReportedLikeTheReference() {
        // Given enough Long.MAX_VALUEs that every lane and every order overflows
        long[] values = new long[100];
        java.util.Arrays.fill(values, Long.MAX_VALUE);

        // When / Then
        assertThat(sut.sum(values, PType.I64)).isEmpty();
        assertThat(reference.sum(values, PType.I64)).isEmpty();
    }

    @Test
    void sum_unsigned64_clearOverflowIsReportedLikeTheReference() {
        // Given enough -1s (2^64 - 1 each) that every lane and every order carries
        long[] values = new long[100];
        java.util.Arrays.fill(values, -1L);

        // When / Then
        assertThat(sut.sum(values, PType.U64)).isEmpty();
        assertThat(reference.sum(values, PType.U64)).isEmpty();
    }

    @Test
    void sum_signed64_oppositeHugeValuesInOneLane_overflowPerLane_unlikeTheSequentialReference() {
        // Given alternating huge values of opposite sign. Every vector length is even, so every positive
        // value lands in the same lane: that lane's running sum overflows although the total is zero
        long[] values = new long[64];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 2 == 0 ? Long.MAX_VALUE / 2 : -(Long.MAX_VALUE / 2);
        }

        // When / Then the documented difference from Rust: lane-wise overflow detection (docs/compatibility.md)
        assertThat(reference.sum(values, PType.I64)).hasValue(0L);
        assertThat(sut.sum(values, PType.I64)).isEmpty();
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
            assertThat(sut.minMax(floats, PType.F32)).as("F32 %s", java.util.Arrays.toString(floats))
                    .containsExactly(reference.minMax(floats, PType.F32));
            assertThat(sut.minMax(doubles, PType.F64)).as("F64 %s", java.util.Arrays.toString(doubles))
                    .containsExactly(reference.minMax(doubles, PType.F64));
        }
    }

    @Test
    void minMax_emptyArray_throws() {
        // Given / When / Then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.minMax(new int[0], PType.I32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allEqual_floatsCompareRawBits() {
        // Given arrays equal under == but not bit for bit, long enough for the vector loop
        float[] zeros = new float[40];
        zeros[39] = -0.0f;
        double[] nans = new double[40];
        java.util.Arrays.fill(nans, Double.NaN);
        nans[20] = Double.longBitsToDouble(0x7ff8000000000001L);

        // When / Then
        assertThat(sut.allEqual(zeros, PType.F32)).isFalse().isEqualTo(reference.allEqual(zeros, PType.F32));
        assertThat(sut.allEqual(nans, PType.F64)).isFalse().isEqualTo(reference.allEqual(nans, PType.F64));
    }

    @Test
    void allEqual_booleans_matchReference() {
        // Given
        for (int length : LENGTHS) {
            boolean[] constant = new boolean[length];
            java.util.Arrays.fill(constant, true);
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

    @Test
    void runs_floatsWithNaNAndSignedZero_matchReference() {
        // Given NaN, which always differs from itself, and 0.0 / -0.0, which compare equal
        float[] floats = {Float.NaN, Float.NaN, 0.0f, -0.0f, 0.0f, 1f, 1f, Float.NaN, 2f, 2f, 0f, -0f, 7f, 7f, 7f, 7f, 8f};
        double[] doubles = {Double.NaN, Double.NaN, 0.0, -0.0, 0.0, 1.0, 1.0, Double.NaN, 2.0, 2.0, 0.0, -0.0, 7.0, 7.0};

        // When / Then
        assertThat(sut.runs(floats, PType.F32)).isEqualTo(reference.runs(floats, PType.F32));
        assertThat(sut.runs(doubles, PType.F64)).isEqualTo(reference.runs(doubles, PType.F64));
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
            case byte[] a -> java.util.Arrays.fill(a, (byte) -1);
            case short[] a -> java.util.Arrays.fill(a, (short) -1);
            case int[] a -> java.util.Arrays.fill(a, -1);
            default -> throw new IllegalArgumentException(array.getClass().toString());
        }
    }

    private static Object narrowCarrier(PType ptype, int length) {
        return switch (ptype) {
            case I8, U8 -> new byte[length];
            case I16, U16 -> new short[length];
            case I32, U32 -> new int[length];
            case I64, U64 -> new long[length];
            case F16 -> new short[length];
            case F32 -> new float[length];
            case F64 -> new double[length];
        };
    }

    /// The sum of the absolute values: the scale of the rounding error a reordered float sum can have.
    private static double magnitude(Object values, PType ptype) {
        double total = 0;
        switch (values) {
            case float[] f -> {
                for (float v : f) {
                    total += Math.abs(v);
                }
            }
            case double[] d -> {
                for (double v : d) {
                    total += Math.abs(v);
                }
            }
            case short[] h -> {
                for (short v : h) {
                    total += Math.abs(Float.float16ToFloat(v));
                }
            }
            default -> throw new IllegalArgumentException(ptype.toString());
        }
        return total;
    }
}
