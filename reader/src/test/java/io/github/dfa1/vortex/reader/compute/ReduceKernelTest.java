package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

class ReduceKernelTest {

    private static final Arena ARENA = Arena.ofAuto();

    @Test
    void sumFoldsAllSelectedValues() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40);

        // When summing every position
        Number result = Reductions.SUM.apply(array, Mask.allTrue(array.length()));

        // Then the total is the integer sum, boxed as a Long
        assertThat(result).isEqualTo(100L).isInstanceOf(Long.class);
    }

    @Test
    void countCountsAllSelectedValues() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40);

        // When counting every position
        Long result = Reductions.COUNT.apply(array, Mask.allTrue(array.length()));

        // Then all four are counted
        assertThat(result).isEqualTo(4L);
    }

    @Test
    void minAndMaxReturnExtremes() {
        // Given a plain long array
        Array array = ComputeArrays.longArray(ARENA, 30, 10, 40, 20);

        // When reducing to the extremes
        Object min = Reductions.MIN.apply(array, Mask.allTrue(array.length()));
        Object max = Reductions.MAX.apply(array, Mask.allTrue(array.length()));

        // Then the smallest and largest values are returned
        assertThat(min).isEqualTo(10L);
        assertThat(max).isEqualTo(40L);
    }

    @Test
    void reductionsHonorMaskedSubset() {
        // Given a long array and a range mask selecting only indices 1 and 2
        Array array = ComputeArrays.longArray(ARENA, 10, 20, 30, 40);
        Mask subset = new Mask.RangeMask(1, 3, array.length());

        // When reducing under the subset
        Number sum = Reductions.SUM.apply(array, subset);
        Long count = Reductions.COUNT.apply(array, subset);
        Object min = Reductions.MIN.apply(array, subset);
        Object max = Reductions.MAX.apply(array, subset);

        // Then only the selected positions contribute
        assertThat(sum).isEqualTo(50L);
        assertThat(count).isEqualTo(2L);
        assertThat(min).isEqualTo(20L);
        assertThat(max).isEqualTo(30L);
    }

    @Test
    void nullsAreSkipped() {
        // Given a masked array with nulls at indices 1 and 3
        MaskedArray array = ComputeArrays.maskedLongArray(ARENA,
                new long[]{10, 20, 30, 40}, new boolean[]{true, false, true, false});
        Mask all = Mask.allTrue(array.length());

        // When reducing over every position
        // Then null positions never contribute
        assertThat(Reductions.SUM.apply(array, all)).isEqualTo(40L);
        assertThat(Reductions.COUNT.apply(array, all)).isEqualTo(2L);
        assertThat(Reductions.MIN.apply(array, all)).isEqualTo(10L);
        assertThat(Reductions.MAX.apply(array, all)).isEqualTo(30L);
    }

    @Test
    void emptyArrayYieldsIdentityResults() {
        // Given an empty integer array
        Array array = ComputeArrays.longArray(ARENA);
        Mask all = Mask.allTrue(array.length());

        // When reducing
        // Then SUM/COUNT are the additive identity and MIN/MAX are absent (mirrors Rust)
        assertThat(Reductions.SUM.apply(array, all)).isEqualTo(0L);
        assertThat(Reductions.COUNT.apply(array, all)).isEqualTo(0L);
        assertThat(Reductions.MIN.apply(array, all)).isNull();
        assertThat(Reductions.MAX.apply(array, all)).isNull();
    }

    @Test
    void allNullYieldsIdentityResults() {
        // Given an all-null masked array
        MaskedArray array = ComputeArrays.maskedLongArray(ARENA,
                new long[]{1, 2, 3}, new boolean[]{false, false, false});
        Mask all = Mask.allTrue(array.length());

        // When reducing
        // Then the results match the empty case: SUM/COUNT identity, MIN/MAX null
        assertThat(Reductions.SUM.apply(array, all)).isEqualTo(0L);
        assertThat(Reductions.COUNT.apply(array, all)).isEqualTo(0L);
        assertThat(Reductions.MIN.apply(array, all)).isNull();
        assertThat(Reductions.MAX.apply(array, all)).isNull();
    }

    @Test
    void floatColumnReturnsDoubleResults() {
        // Given an f64 array
        Array array = ComputeArrays.doubleArray(ARENA, 1.5, 2.5, 3.0);
        Mask all = Mask.allTrue(array.length());

        // When reducing
        Number sum = Reductions.SUM.apply(array, all);
        Object min = Reductions.MIN.apply(array, all);
        Object max = Reductions.MAX.apply(array, all);

        // Then the float column folds into Double results, matching ArrayStats' sum boxing
        assertThat(sum).isEqualTo(7.0).isInstanceOf(Double.class);
        assertThat(min).isEqualTo(1.5);
        assertThat(max).isEqualTo(3.0);
    }

    @Test
    void unsignedColumnUsesUnsignedSemantics() {
        // Given a u64 column where one value sets the sign bit (2^63) — a signed reduce would call
        // it the minimum, but unsigned it is the maximum
        long highBit = Long.MIN_VALUE; // raw bits of 2^63
        Array array = ComputeArrays.unsignedLongArray(ARENA, 1L, highBit, 5L);
        Mask all = Mask.allTrue(array.length());

        // When reducing
        Number sum = Reductions.SUM.apply(array, all);
        Object min = Reductions.MIN.apply(array, all);
        Object max = Reductions.MAX.apply(array, all);

        // Then MIN/MAX honor unsigned order and SUM wraps in two's complement like ZoneReducer
        assertThat(min).isEqualTo(1L);
        assertThat(max).isEqualTo(highBit);
        assertThat(sum).isEqualTo(1L + highBit + 5L);
    }
}
