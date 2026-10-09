package io.github.dfa1.vortex.writer.encode;

/// Fast-path verdict an [EncodingEncoder] returns from an array's stats, letting the
/// [CascadingCompressor] skip the expensive sample-encode probe: Rust's `CompressionEstimate`.
///
/// Candidates are scored as Rust scores them, by compression ratio (raw bytes over encoded
/// bytes, higher wins, only above 1 counts): a [Ratio] supplies it from stats alone, [#COMPLETE]
/// has it measured by encoding a sample.
public sealed interface Estimate {

    /// Encoder is incompatible with this input, or cannot win on it; exclude it.
    Estimate SKIP = Verdict.SKIP;

    /// Encoder is provably the best choice; pick it immediately and short-circuit.
    Estimate ALWAYS_USE = Verdict.ALWAYS_USE;

    /// No shortcut: measure this encoder by encoding a sample (Rust's `DeferredEstimate::Sample`).
    Estimate COMPLETE = Verdict.COMPLETE;

    /// An estimated compression ratio, which competes with sampled ones without encoding anything
    /// (Rust's `EstimateVerdict::Ratio`).
    ///
    /// @param ratio raw bytes over estimated encoded bytes
    /// @return the estimate
    static Estimate ratio(double ratio) {
        return new Ratio(ratio);
    }

    /// The verdicts that carry no value.
    enum Verdict implements Estimate {
        /// See [Estimate#SKIP].
        SKIP,
        /// See [Estimate#ALWAYS_USE].
        ALWAYS_USE,
        /// See [Estimate#COMPLETE].
        COMPLETE
    }

    /// See [Estimate#ratio(double)].
    ///
    /// @param value raw bytes over estimated encoded bytes
    record Ratio(double value) implements Estimate {
    }
}
