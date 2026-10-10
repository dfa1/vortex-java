package io.github.dfa1.vortex.core.simd;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        sut = new VectorApiSimdOperations(reference);
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
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void minMax_matchesReference(PType ptype) {
        // Given random full-range data at every length: values near the sign boundary, where the
        // unsigned flip would swap min and max if wrong
        for (int length : LENGTHS) {
            if (length == 0) {
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
}
