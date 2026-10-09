package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/// Verdicts ported from Rust's schemes (`vortex-btrblocks/src/schemes`). Each case pins the
/// condition Rust skips on, because a missing skip is not caught by any round-trip test: the
/// cascade just measures the candidate on a sample instead, and a sample can mislead. Taxi's
/// `tip_amount` was 4.4x vortex-jni's size because 1024-row samples rarely held one of its rare
/// negative tips, so bit-packing measured narrow and then packed the full chunk at 64 bits.
class SchemeVerdictTest {

    private static final WriteRegistry REGISTRY = WriteRegistry.builder().registerDefaults().build();
    private static final EncodeContext REAL = EncodeContext.ofDepth(3, Arena.ofAuto(), REGISTRY);
    private static final EncodeContext SAMPLE = REAL.withSampling();
    private static final EncodeContext FINISHED = EncodeContext.ofDepth(0, Arena.ofAuto(), REGISTRY);

    private static ArrayAndStats longs(long... values) {
        return new ArrayAndStats(DType.I64, values, StatsOptions.DISTINCT_AND_TOP);
    }

    @Nested
    class BitPacking {

        private final BitpackedEncodingEncoder sut = new BitpackedEncodingEncoder();

        @Test
        void skipsAnArrayWithANegative() {
            // Given: one negative among many small values, as in tip_amount's ALP integers
            ArrayAndStats data = longs(5, 7, 3, -80, 9);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void samplesANonNegativeArray() {
            // Given
            ArrayAndStats data = longs(5, 7, 3, 0, 9);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.COMPLETE);
        }

        @Test
        void neverTreatsAnUnsigned64AboveTwoToThe63AsNegative() {
            // Given: raw bits negative as a signed long, but U64 has no negatives
            ArrayAndStats data = new ArrayAndStats(DType.U64, new long[]{-1L, 3}, StatsOptions.NONE);

            // When
            Estimate result = sut.expectedRatio(DType.U64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.COMPLETE);
        }
    }

    @Nested
    class ZigZag {

        private final ZigZagEncodingEncoder sut = new ZigZagEncodingEncoder();

        @Test
        void samplesOnlyArraysWithNegatives() {
            // Given
            ArrayAndStats negative = longs(1, -2, 3);
            ArrayAndStats nonNegative = longs(1, 2, 3);

            // When
            Estimate result = sut.expectedRatio(DType.I64, negative, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.COMPLETE);
            assertThat(sut.expectedRatio(DType.I64, nonNegative, REAL)).isEqualTo(Estimate.SKIP);
        }

        @Test
        void skipsOnceCascadingIsFinished() {
            // Given: ZigZag only pays off through its cascaded child
            ArrayAndStats data = longs(1, -2, 3);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, FINISHED);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }
    }

    @Nested
    class Alp {

        @Test
        void skipsOnceCascadingIsFinished() {
            // Given
            AlpEncodingEncoder sut = new AlpEncodingEncoder();
            ArrayAndStats data = new ArrayAndStats(DType.F64, new double[]{1.5, 2.25}, StatsOptions.NONE);

            // When
            Estimate result = sut.expectedRatio(DType.F64, data, FINISHED);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
            assertThat(sut.expectedRatio(DType.F64, data, REAL)).isEqualTo(Estimate.COMPLETE);
        }
    }

    @Nested
    class Sequence {

        private final SequenceEncodingEncoder sut = new SequenceEncodingEncoder();

        @Test
        void scoresARealSequenceAtHalfItsLength() {
            // Given
            ArrayAndStats data = longs(10, 13, 16, 19, 22, 25);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then: Rust's len / 2
            assertThat(result).isEqualTo(Estimate.ratio(3.0));
        }

        @Test
        void neverOnASample() {
            // Given: the cascade samples in random strides, so a sample says nothing of the array
            ArrayAndStats data = longs(10, 13, 16, 19);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, SAMPLE);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void skipsWhenNotASequence() {
            // Given: all distinct, so only the direct check rules it out
            ArrayAndStats data = longs(10, 13, 17, 19);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }
    }

    @Nested
    class Constant {

        @Test
        void neverOnASample() {
            // Given: Rust detects constants in the compressor, never on a sample
            ConstantEncodingEncoder sut = new ConstantEncodingEncoder();
            ArrayAndStats data = longs(4, 4, 4);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, SAMPLE);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
            assertThat(sut.expectedRatio(DType.I64, data, REAL)).isEqualTo(Estimate.ALWAYS_USE);
        }

        @Test
        void aConstantStringArrayAlwaysUsesItNeverOnASample() {
            // Given: a column whose every row is the same symbol, as the klines `symbol` chunks.
            // Rust's compressor short-circuits any constant leaf, strings included
            ConstantEncodingEncoder sut = new ConstantEncodingEncoder();
            ArrayAndStats data = new ArrayAndStats(DType.UTF8, new String[]{"BTCUSDT", "BTCUSDT", "BTCUSDT"}, StatsOptions.NONE);

            // When
            Estimate real = sut.expectedRatio(DType.UTF8, data, REAL);
            Estimate sampled = sut.expectedRatio(DType.UTF8, data, SAMPLE);

            // Then
            assertThat(real).isEqualTo(Estimate.ALWAYS_USE);
            assertThat(sampled).isEqualTo(Estimate.SKIP);
        }

        @Test
        void skipsStringsThatDifferOrAreEmpty() {
            // Given
            ConstantEncodingEncoder sut = new ConstantEncodingEncoder();
            ArrayAndStats different = new ArrayAndStats(DType.UTF8, new String[]{"a", "a", "b"}, StatsOptions.NONE);
            ArrayAndStats empty = new ArrayAndStats(DType.UTF8, new String[0], StatsOptions.NONE);

            // When / Then
            assertThat(sut.expectedRatio(DType.UTF8, different, REAL)).isEqualTo(Estimate.SKIP);
            assertThat(sut.expectedRatio(DType.UTF8, empty, REAL)).isEqualTo(Estimate.SKIP);
        }
    }

    @Nested
    class FrameOfReference {

        private final FrameOfReferenceEncodingEncoder sut = new FrameOfReferenceEncodingEncoder();

        @Test
        void estimatesTypeWidthOverOffsetWidth() {
            // Given: 1000..1003 spans 3 (2 bits), while max 1003 needs 10 bits to bit-pack
            ArrayAndStats data = longs(1000, 1001, 1002, 1003);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then: Rust's full_width / for_bitwidth = 64 / 2
            assertThat(result).isEqualTo(Estimate.ratio(32.0));
        }

        @Test
        void skipsWhenBitPackingIsAsNarrow() {
            // Given: 1..7 spans 6 (3 bits) and max 7 also packs in 3 bits
            ArrayAndStats data = longs(1, 4, 7);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void skipsAZeroMinimumAndAConstant() {
            // Given
            ArrayAndStats zeroMin = longs(0, 500, 1000);
            ArrayAndStats constant = longs(9, 9, 9);

            // When
            Estimate result = sut.expectedRatio(DType.I64, zeroMin, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
            assertThat(sut.expectedRatio(DType.I64, constant, REAL)).isEqualTo(Estimate.SKIP);
        }

        @Test
        void estimatesNegativesWhateverTheMaximum() {
            // Given: with a negative, bit-packing is no alternative, so no width comparison
            ArrayAndStats data = longs(-2, 1);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then: span 3 -> 2 bits
            assertThat(result).isEqualTo(Estimate.ratio(32.0));
        }
    }

    @Nested
    class Dict {

        private final DictEncodingEncoder sut = new DictEncodingEncoder();

        @Test
        void integerRatio_matchesRustsIntDictEstimate() {
            // Given: 8 I64 values, 2 distinct, runs of 4
            ArrayAndStats data = longs(7, 7, 7, 7, 9, 9, 9, 9);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then: before 8*64 = 512 bits; values 64*2 = 128; codes 2 bits wide:
            // min(2*8, (2+32)*2 runs) = 16; 512 / 144
            assertThat(result).isEqualTo(Estimate.ratio(512.0 / 144.0));
        }

        @Test
        void skipsWhenMoreThanHalfAreDistinct() {
            // Given: 3 distinct of 5 values; Rust's `distinct > value_count / 2` (integer division)
            ArrayAndStats data = longs(1, 1, 2, 2, 3);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void floatsDeferToTheSample() {
            // Given: Rust's FloatDictScheme samples rather than estimating
            ArrayAndStats data = new ArrayAndStats(DType.F64, new double[]{1.5, 1.5, 2.5, 2.5}, StatsOptions.DISTINCT_AND_TOP);

            // When
            Estimate result = sut.expectedRatio(DType.F64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.COMPLETE);
        }
    }

    @Nested
    class Sparse {

        private final SparseEncodingEncoder sut = new SparseEncodingEncoder();

        private static long[] dominated(long fill, int n, int others) {
            long[] data = new long[n];
            java.util.Arrays.fill(data, fill);
            for (int i = 0; i < others; i++) {
                data[i * (n / others)] = 1_000 + i;
            }
            return data;
        }

        @Test
        void estimatesANonZeroDominantValue() {
            // Given: 95 of 100 values are 42 — Java used to require the fill to be 0
            ArrayAndStats data = new ArrayAndStats(DType.I64, dominated(42, 100, 5), StatsOptions.DISTINCT_AND_TOP);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then: Rust's value_count / (value_count - top) = 100 / 5
            assertThat(result).isEqualTo(Estimate.ratio(20.0));
        }

        @Test
        void skipsBelowNinetyPercent() {
            // Given: 89 of 100
            ArrayAndStats data = new ArrayAndStats(DType.I64, dominated(42, 100, 11), StatsOptions.DISTINCT_AND_TOP);

            // When
            Estimate result = sut.expectedRatio(DType.I64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }

        @Test
        void skipsFloats() {
            // Given: Rust's float sparse scheme only takes null-dominated arrays
            double[] values = new double[100];
            values[3] = 1.5;
            ArrayAndStats data = new ArrayAndStats(DType.F64, values, StatsOptions.DISTINCT_AND_TOP);

            // When
            Estimate result = sut.expectedRatio(DType.F64, data, REAL);

            // Then
            assertThat(result).isEqualTo(Estimate.SKIP);
        }
    }

    @Nested
    class Stats {

        @Test
        void unsigned64MinMaxAreUnsigned() {
            // Given: 2^64 - 1 is the U64 maximum, not -1
            long[] data = {-1L, 7, 3};

            // When
            ArrayStats result = ArrayStats.compute(PType.U64, data, StatsOptions.NONE);

            // Then
            assertThat(result.min()).isEqualTo(3);
            assertThat(result.max()).isEqualTo(-1L);
            assertThat(result.minIsNegative(PType.U64)).isFalse();
            assertThat(result.maxIlog2(PType.U64)).isEqualTo(63);
        }

        @Test
        void maxIlog2_readsANegativeMaxAsUnsignedOfItsWidth() {
            // Given: Rust's `(max as u32).checked_ilog2()` for an all-negative I32 array
            int[] data = {-5, -1};

            // When
            ArrayStats result = ArrayStats.compute(PType.I32, data, StatsOptions.NONE);

            // Then
            assertThat(result.maxIlog2(PType.I32)).isEqualTo(31);
            assertThat(result.maxMinusMin()).isEqualTo(4);
        }
    }
}
