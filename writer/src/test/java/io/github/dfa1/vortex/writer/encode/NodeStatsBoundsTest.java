package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A node's min and max come from the stats the scheme competition computed when it did, as Rust reads
/// them off the array's cache, and from a scan when it did not; the two must be the same bounds.
class NodeStatsBoundsTest {

    static Stream<Arguments> columns() {
        boolean[] someNull = {true, false, true, true, false, true};
        boolean[] allNull = {false, false, false};
        return Stream.of(
                Arguments.of("i8", PType.I8, new byte[]{5, -3, 9, 0, 120, -128}),
                Arguments.of("u16", PType.U16, new short[]{5, 3, (short) 65_535, 0}),
                Arguments.of("i32", PType.I32, new int[]{7, -2_000_000_000, 2_000_000_000, 0}),
                Arguments.of("u32", PType.U32, new int[]{7, -1, 3}),
                Arguments.of("i64", PType.I64, new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE}),
                // a value past 2^63 is a negative long, and the maximum
                Arguments.of("u64 past 2^63", PType.U64, new long[]{5, -1L, 1L << 63, 2}),
                Arguments.of("i32, with nulls", PType.I32,
                        new NullableData(new int[]{100, 0, -50, 70, 0, 9}, someNull)),
                Arguments.of("u64, with nulls", PType.U64,
                        new NullableData(new long[]{3, 0, -1L, 8, 0, 4}, someNull)),
                Arguments.of("i16, all null", PType.I16, new NullableData(new short[]{0, 0, 0}, allNull)),
                Arguments.of("i64, one value", PType.I64, new long[]{42}),
                Arguments.of("i32, empty", PType.I32, new int[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void integerBounds_fromComputedStats_areTheScannedBounds(String name, PType ptype, Object data) {
        // Given one array whose stats were computed, as the competition does, and one whose were not
        DType dtype = new DType.Primitive(ptype, data instanceof NullableData);
        ArrayAndStats computed = new ArrayAndStats(dtype, data, StatsOptions.defaults());
        computed.stats();
        ArrayAndStats untouched = new ArrayAndStats(dtype, data, StatsOptions.defaults());
        DType.Primitive primitive = (DType.Primitive) dtype;

        // When
        MinMax reused = CascadingCompressor.integerBounds(primitive, data, computed);
        MinMax scanned = CascadingCompressor.integerBounds(primitive, data, untouched);
        MinMax expected = ZoneMapStats.of(dtype, data);

        // Then
        assertThat(reused).isEqualTo(expected);
        assertThat(scanned).isEqualTo(expected);
        assertThat(untouched.statsIfComputed()).as("the scan does not force the stats").isNull();
    }

    @Test
    void integerBounds_withoutAnInput_scans() {
        // Given
        DType dtype = new DType.Primitive(PType.I32, false);
        int[] data = {4, 1, 9};

        // When
        MinMax result = CascadingCompressor.integerBounds((DType.Primitive) dtype, data, null);

        // Then
        assertThat(result).isEqualTo(ZoneMapStats.of(dtype, data));
    }
}
