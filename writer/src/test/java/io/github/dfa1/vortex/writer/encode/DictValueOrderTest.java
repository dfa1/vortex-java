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
}
