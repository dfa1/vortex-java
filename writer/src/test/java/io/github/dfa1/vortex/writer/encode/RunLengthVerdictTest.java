package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// RLE and RunEnd decide as Rust's `RLEScheme` / `RunEndScheme` do: skip unless runs average at
/// least 4 values (`RUN_LENGTH_THRESHOLD`, `RUN_END_THRESHOLD`). Without the skip both were
/// trial-encoded on every sample, the largest allocation of a cascading write.
class RunLengthVerdictTest {

    static Stream<Arguments> encodersAndRunLengths() {
        return Stream.of(
                Arguments.of(new RleEncodingEncoder(), 3, Estimate.SKIP),
                Arguments.of(new RleEncodingEncoder(), 4, Estimate.COMPLETE),
                Arguments.of(new RunEndEncodingEncoder(), 3, Estimate.SKIP),
                Arguments.of(new RunEndEncodingEncoder(), 4, Estimate.COMPLETE));
    }

    @ParameterizedTest
    @MethodSource("encodersAndRunLengths")
    void expectedRatio_skipsBelowFourValuesPerRun(EncodingEncoder sut, int runLength, Estimate expected) {
        // Given: 1200 values in runs of exactly `runLength` (1200 divides by both 3 and 4)
        long[] data = new long[1_200];
        for (int i = 0; i < data.length; i++) {
            data[i] = i / runLength;
        }

        // When
        Estimate result = sut.expectedRatio(DType.I64, new ArrayAndStats(DType.I64, data, StatsOptions.NONE),
                EncodeContext.ofDepth(3, Arena.ofAuto(), WriteRegistry.builder().registerDefaults().build()));

        // Then
        assertThat(result).isEqualTo(expected);
    }
}
