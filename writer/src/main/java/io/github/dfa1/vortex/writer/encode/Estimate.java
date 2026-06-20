package io.github.dfa1.vortex.writer.encode;

/// Fast-path verdict an [EncodingEncoder] returns from pre-computed [ArrayStats],
/// letting the [CascadingCompressor] skip the expensive sample-encode probe.
public enum Estimate {

    /// Encoder is incompatible with this input; exclude it from the competition.
    SKIP,

    /// Encoder is provably the best choice; pick it immediately and short-circuit.
    ALWAYS_USE,

    /// No shortcut: run the complete sample-encode evaluation to size this encoder.
    COMPLETE
}
