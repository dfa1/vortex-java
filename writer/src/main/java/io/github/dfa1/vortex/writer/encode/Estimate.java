package io.github.dfa1.vortex.writer.encode;

/// Compression estimate returned by an [EncodingEncoder] given pre-computed [ArrayStats].
///
/// Three terminal verdicts:
/// - [Skip] — encoder is incompatible with this input; do not consider.
/// - [AlwaysUse] — encoder is provably the best choice; pick immediately.
/// - [Ratio] — encoder estimates this compression ratio; compete against other ratios.
///
/// Mirrors `vortex-compressor::estimate::EstimateVerdict`.
public sealed interface Estimate {

    /// Skip this encoder for this input.
    record Skip() implements Estimate {
    }

    /// Pick this encoder immediately; short-circuit the cascade.
    record AlwaysUse() implements Estimate {
    }

    /// Estimated compression ratio. Higher is better. Must be greater than 1.0 to compete.
    ///
    /// @param ratio rawBytes / encodedBytes; higher means smaller encoded output
    record Ratio(double ratio) implements Estimate {
    }

    Estimate SKIP = new Skip();
    Estimate ALWAYS_USE = new AlwaysUse();

    /// @return the [Skip] verdict singleton
    static Estimate skip() {
        return SKIP;
    }

    /// @return the [AlwaysUse] verdict singleton
    static Estimate alwaysUse() {
        return ALWAYS_USE;
    }

    /// @param ratio rawBytes / encodedBytes; higher means smaller encoded output
    /// @return a [Ratio] estimate carrying the given ratio
    static Estimate ratio(double ratio) {
        return new Ratio(ratio);
    }
}
