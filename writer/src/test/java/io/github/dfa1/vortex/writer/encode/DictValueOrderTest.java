package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The replay of Rust's distinct-values map. `DictValueOrderInteropIntegrationTest` checks the
/// order against vortex-jni; these pin what holds on any host.
class DictValueOrderTest {

    /// Worked by hand from `FxHasher` and `hashbrown`: 0, 1 and 2 hash to buckets 0, 3 and 3 of a
    /// 4-bucket table, so 2 probes past the end, lands on the full bucket 0 and falls back to the
    /// first empty one, 1 (`fix_insert_index`). Iteration is then 0, 2, 1.
    @Test
    void collisionInASmallTableTakesTheFirstEmptyBucket() {
        // Given
        long[] raw = {0, 1, 2};

        // When
        int[] result = DictValueOrder.ranks(PType.I32, raw, 3, 3, false);

        // Then
        assertThat(result).containsExactly(0, 2, 1);
    }

    static Stream<Arguments> columns() {
        Random random = new Random(3);
        return Stream.of(
                // dense counter: a narrow range, inserted in ascending order into a growing map
                Arguments.of(PType.I16, random.longs(300, -50, 200).distinct().toArray(), 8192),
                // hashed counter: a wide range, inserted as it comes into a map sized for len / 2
                Arguments.of(PType.I64, random.longs(3000).distinct().toArray(), 8192),
                Arguments.of(PType.U32, random.longs(500, 0, 1L << 32).distinct().toArray(), 1000),
                // float set
                Arguments.of(PType.F64, random.doubles(700).mapToLong(Double::doubleToRawLongBits).toArray(), 1400));
    }

    @ParameterizedTest
    @MethodSource("columns")
    void ranksAreAPermutationOfTheCodes(PType ptype, long[] raw, int len) {
        // When
        int[] result = DictValueOrder.ranks(ptype, raw, raw.length, len, true);

        // Then
        int[] sorted = result.clone();
        Arrays.sort(sorted);
        assertThat(sorted).isEqualTo(IntStream.range(0, raw.length).toArray());
    }

    /// An unsigned byte arrives sign-extended, so 200 is -56: its span from 10 read as negative,
    /// that is huge, and sent a narrow `U8` column down the hashed path. It must order as the same
    /// numbers in an `I16`, which hash to the same bits and also count densely.
    @Test
    void unsignedByteWithTopBitSetCountsDensely() {
        // Given
        long[] values = new Random(9).longs(0, 256).distinct().limit(100).toArray();
        long[] asU8 = Arrays.stream(values).map(v -> (byte) v).toArray();

        // When
        int[] result = DictValueOrder.ranks(PType.U8, asU8, asU8.length, 8192, false);

        // Then
        assertThat(result).isEqualTo(DictValueOrder.ranks(PType.I16, values, values.length, 8192, false));
    }

    /// 24 keys hashing to buckets 0 to 3 of 64 fill a run past 16 buckets: x86 then probes the
    /// next 16, other hosts skip to the group after next. The interop test runs this input on each
    /// CI host against its own vortex-jni.
    @Test
    void groupWidthPlacesARunPastSixteenBucketsDifferently() {
        // Given
        long[] raw = collidingInts(24, 64);

        // When
        int[] result = DictValueOrder.ranks(PType.I32, raw, raw.length, 64, false, 16);

        // Then
        assertThat(result).isNotEqualTo(DictValueOrder.ranks(PType.I32, raw, raw.length, 64, false, 8));
    }

    /// 28 distinct floats fill a set sized for 56 rows exactly; a later duplicate grows it, so
    /// Rust iterates a table twice the size.
    @Test
    void duplicateOnAFullFloatSetGrowsIt() {
        // Given
        long[] raw = new Random(13).doubles(28).mapToLong(Double::doubleToRawLongBits).toArray();

        // When
        int[] result = DictValueOrder.ranks(PType.F64, raw, raw.length, 56, true);

        // Then
        assertThat(result).isNotEqualTo(DictValueOrder.ranks(PType.F64, raw, raw.length, 56, false));
    }

    @Test
    void noValuesNoRanks() {
        // When
        int[] result = DictValueOrder.ranks(PType.I32, new long[0], 0, 10, false);

        // Then
        assertThat(result).isEmpty();
    }

    /// `hashbrown` scans 16 control bytes at a time with SSE2 on x86 and 8 elsewhere (NEON, or the
    /// generic `u64` group).
    @ParameterizedTest
    @CsvSource({"amd64,16", "x86_64,16", "x86,16", "aarch64,8", "riscv64,8"})
    void groupWidthFollowsTheHost(String arch, int expected) {
        // When
        int result = DictValueOrder.groupWidth(arch);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    /// `count` wide `I32` values whose `FxHasher` hash falls in buckets 0 to 3 of a table of
    /// `buckets`, as `DictValueOrderInteropIntegrationTest#zoneMinsPilingUpPastAGroup` draws them.
    private static long[] collidingInts(int count, int buckets) {
        Random random = new Random(17);
        long[] out = new long[count];
        int n = 0;
        while (n < count) {
            int v = random.nextInt(1 << 29);
            long hash = Long.rotateLeft((v & 0xFFFFFFFFL) * 0xf1357aea2e62a9c5L, 26);
            if ((hash & (buckets - 1)) < 4) {
                out[n++] = v;
            }
        }
        return out;
    }
}
