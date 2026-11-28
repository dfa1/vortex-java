package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust 0.95's string dictionary estimate: a probe screens out what cannot win, and what can is
/// measured on a dictionary of the complete input, whose output is kept.
class StringDictTrialTest {

    private static final CascadingCompressor COMPRESSOR =
            new CascadingCompressor(List.copyOf(WriteRegistry.loadAll().encoderMap().values()));

    @Test
    void uniqueValuesAreSkippedByTheHeadProbeWithoutSampling() {
        // Given — 5000 distinct values: the head probe sees 2048 of them, none repeated, and the
        // optimistic ratio of a 5000-entry dictionary cannot beat the plain array
        String[] values = new String[5000];
        for (int i = 0; i < values.length; i++) {
            values[i] = "row-" + Integer.toHexString(i * 7919) + "-unique";
        }
        ArrayAndStats input = new ArrayAndStats(DTypes.UTF8, values, StatsOptions.defaults());

        // When
        Estimate result = StringDictTrial.estimate(new DictEncodingEncoder(), COMPRESSOR, DTypes.UTF8, input,
                EncodeTestHelper.cascadingCtx(), null, () -> {
                    throw new AssertionError("the head probe should reject before any sample");
                });

        // Then
        assertThat(result).isEqualTo(Estimate.SKIP);
        assertThat(input.trial()).isNull();
    }

    @Test
    void fewDistinctValuesRunTheTrialAndKeepItsOutput() {
        // Given — 5000 rows over 10 long values: a sample would see them all, but the trial
        // measures the complete dictionary
        String[] values = new String[5000];
        for (int i = 0; i < values.length; i++) {
            values[i] = "a fairly long category name number " + i % 10;
        }
        ArrayAndStats input = new ArrayAndStats(DTypes.UTF8, values, StatsOptions.defaults());

        // When
        Estimate result = StringDictTrial.estimate(new DictEncodingEncoder(), COMPRESSOR, DTypes.UTF8, input,
                EncodeTestHelper.cascadingCtx(), null, () -> {
                    throw new AssertionError("the trial should run, not the sample");
                });

        // Then
        assertThat(result).isInstanceOf(Estimate.Ratio.class);
        assertThat(((Estimate.Ratio) result).value()).isGreaterThan(10.0);
        assertThat(input.trialScheme()).isEqualTo(EncodingId.VORTEX_DICT);
        assertThat(input.trial().rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_DICT);
    }
}
